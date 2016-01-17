(ns taoensso.carmine.protocol
  "Facilities for actually communicating with Redis server using its
  request/response protocol. Originally adapted from Accession.
  Ref. http://redis.io/topics/protocol"
  {:author "Peter Taoussanis"}
  (:require [clojure.string       :as str]
            [taoensso.encore      :as encore]
            [taoensso.nippy       :as nippy]
            [taoensso.nippy.tools :as nippy-tools])
  (:import  [java.io DataInputStream BufferedOutputStream]
            [clojure.lang Keyword]))

;;; Outline (Carmine v3+)
;; * Dynamic context is established with `carmine/wcar`.
;; * Commands executed w/in this context push their requests (vectors) into
;;   context's request queue. Requests may have metadata for Cluster keyslots &
;;   parsers. Parsers may have metadata as a convenient+composable way of
;;   communicating special request requirements (:raw-bulk?, :thaw-opts, etc.).
;; * On `with-reply`, nested `wcar`, or `execute-requests` - queued requests
;;   will actually be sent to server as pipeline.
;; * For non-listener modes, corresponding replies will then immediately be
;;   received, parsed, + returned.

;;;; Dynamic context stuff

(defrecord Context [conn      ; active Connection
                    req-queue ; [<pulled-reqs> [<queued-req> ...]] atom
                    ])
(def ^:dynamic *context* nil) ; Context
(def ^:dynamic *parser*  nil) ; ifn (with optional meta) or nil
(def no-context-ex
  (ex-info
    (str "Redis commands must be called within the context of a"
         " connection to Redis server. See `wcar`.") {}))

;;;;

(def *keyslots-server-cache* (atom {}))

(defn- update-keyslots-cache! [keyslot address]
  (swap! *keyslots-server-cache* assoc keyslot address))

(defn- keyslot-move! [moved-exception]
  (let [cause (.getMessage moved-exception)
        [_ keyslot address] (clojure.string/split cause #" ")]
    (update-keyslots-cache! keyslot address)))

(defn- bytestring
  "Redis communicates with clients using a (binary-safe) byte string protocol.
  This is the equivalent of the byte array representation of a Java String."
  ^bytes [^String s] (.getBytes s "UTF-8"))

;;; Request delimiters
(def ^{:tag 'bytes} bs-crlf (bytestring "\r\n"))
(def ^:const ^Integer bs-* (int (first (bytestring "*"))))
(def ^:const ^Integer bs-$ (int (first (bytestring "$"))))

;; Carmine-only markers that'll be used _within_ bulk data to indicate that
;; the data requires special reply handling
(def ^{:tag 'bytes} bs-bin (bytestring "\u0000<")) ; Binary data marker
(def ^{:tag 'bytes} bs-clj (bytestring "\u0000>")) ; Frozen data marker

(defn- ensure-reserved-first-byte [^bytes ba]
  (when (and (> (alength ba) 0) (== (aget ba 0) 0))
    (throw (ex-info "Args can't begin with null terminator" {:ba ba})))
  ba)

(defrecord WrappedRaw [ba])
(defn raw "Forces byte[] argument to be sent to Redis as raw, unencoded bytes."
  [x]
  (cond
    (encore/bytes?        x) (WrappedRaw. x)
    (instance? WrappedRaw x) x
    :else (throw (ex-info "Raw arg must be byte[]" {:x x}))))

(defprotocol IRedisArg
  (coerce-bs [x] "Coerces arbitrary Clojure value to RESP arg, by type."))

(extend-protocol IRedisArg
  String  (coerce-bs [x] (-> (bytestring x)
                             (ensure-reserved-first-byte)))
  Keyword (coerce-bs [x] (-> (bytestring ^String (encore/fq-name x))
                             (ensure-reserved-first-byte)))

  ;;; Simple number types (Redis understands these)
  Long    (coerce-bs [x] (bytestring (Long/toString    x)))
  Double  (coerce-bs [x] (bytestring (Double/toString  x)))
  Float   (coerce-bs [x] (bytestring (Float/toString   x)))
  Integer (coerce-bs [x] (bytestring (Integer/toString x)))

  WrappedRaw (coerce-bs [x] (:ba x))

  ;;; TODO Would be nice if we could avoid the array copies here:
  nil        (coerce-bs [x] (encore/ba-concat bs-clj (nippy-tools/freeze x)))
  Object     (coerce-bs [x] (encore/ba-concat bs-clj (nippy-tools/freeze x))))

(extend encore/bytes-class IRedisArg {:coerce-bs (fn [x] (encore/ba-concat bs-bin x))})

(defmacro ^:private send-*    [out] `(.write ~out bs-*))
(defmacro ^:private send-$    [out] `(.write ~out bs-$))
(defmacro ^:private send-crlf [out] `(.write ~out bs-crlf 0 2))

(defn- send-requests
  "Sends requests to Redis server using its byte string protocol:
    *<no. of args>     crlf
    [$<size of arg N>  crlf
      <arg data>       crlf ...]"
  ;; {:pre [(vector? requests)]}
  [^BufferedOutputStream out requests]
  (encore/backport-run!
    (fn [req-args]
      (when (pos? (count req-args)) ; [] req is dummy req for `return`
        (let [bs-args (:bytestring-req (meta req-args))]

          (send-* out)
          (.write out (bytestring (Integer/toString (int (count bs-args)))))
          (send-crlf out)

          (encore/backport-run!
            (fn [^bytes bs-arg]
              (let [payload-size (alength bs-arg)]
                (send-$ out)
                (.write out (bytestring (Integer/toString (int payload-size))))
                (send-crlf out)
                (.write out bs-arg 0 payload-size) ; Payload
                (send-crlf out)))
            bs-args)

          (comment (.flush out)))))
    requests)
  (.flush out))

(defn get-unparsed-reply
  "Implementation detail.
  BLOCKS to receive a single reply from Redis server and returns the result as
  [<type> <reply>]. Redis will reply to commands with different kinds of replies,
  identified by their first byte, Ref. http://redis.io/topics/protocol:

    * `+` for simple strings -> <string>
    * `:` for integers       -> <long>
    * `-` for error strings  -> <ex-info>
    * `$` for bulk strings   -> <clj>/<raw-bytes>    ; Marked as serialized
                             -> <bytes>/<raw-bytes>  ; Marked as binary
                             -> <string>/<raw-bytes> ; Unmarked
                             -> nil
    * `*` for arrays         -> <vector>
                             -> nil"
  [^DataInputStream in req-opts]
  (let [reply-type (char (.readByte in))]
    (case reply-type
      \+ (.readLine in)
      \: (Long/parseLong (.readLine in))
      \- (let [err-str    (.readLine in)
               err-prefix (re-find #"^\S+" err-str) ; "ERR", "WRONGTYPE", etc.
               err-prefix (when err-prefix
                            (-> err-prefix str/lower-case keyword))]
           (ex-info err-str (if-not err-prefix {} {:prefix err-prefix})))

      ;;; Bulk strings need checking for special in-data markers
      \$ (let [data-size (Integer/parseInt (.readLine in))]
           (if (== data-size -1) nil
             (if (:raw-bulk? req-opts)
               (let [data (byte-array data-size)]
                 (.readFully in data 0 data-size)
                 (.readFully in (byte-array 2) 0 2) ; Discard final crlf
                 data)

               (let [maybe-marked-type? (>= data-size 2)
                     type (if-not maybe-marked-type? :str
                            (let [h (byte-array 2)]
                              (.mark      in 2)
                              (.readFully in h 0 2)
                              (condp encore/ba= h
                                bs-clj :clj
                                bs-bin :bin
                                :str)))
                     marked-type? (not (identical? type :str))
                     payload-size (int (if marked-type? (- data-size 2) data-size))
                     payload      (byte-array payload-size)]

                 (when (and maybe-marked-type? (not marked-type?)) (.reset in))
                 (.readFully in payload 0 payload-size)
                 (.readFully in (byte-array 2) 0 2) ; Discard final crlf

                 (try
                   (case type
                     :str (String. payload 0 payload-size "UTF-8")
                     :clj (if-let [thaw-opts (:thaw-opts req-opts)]
                            (nippy/thaw payload thaw-opts)
                            (nippy/thaw payload))
                     :bin ; payload
                     ;; Workaround #81 (v2.6.0 may have written _serialized_ bins):
                     (if (and ; Nippy header
                           (>= (alength payload) 3)
                           (== (aget payload 0) 78)
                           (== (aget payload 1) 80)
                           (== (aget payload 2) 89))
                       (try (nippy/thaw payload) (catch Exception _ payload))
                       payload))
                   (catch Exception e
                     (let [message (.getMessage e)]
                       (ex-info (str "Bad reply data: " message)
                         {:message message} e))))))))

      \* (let [bulk-count (Integer/parseInt (.readLine in))]
           (if (== bulk-count -1) nil ; Nb was [] with < Carmine v3
             (encore/repeatedly-into* [] bulk-count
               (get-unparsed-reply in req-opts))))

      (throw (ex-info (format "Server returned unknown reply type: %s"
                        (str reply-type))
               {:reply-type reply-type})))))

(defn get-parsed-reply "Implementation detail."
  [^DataInputStream in ?parser]
  (let [;; As an implementation detail, parser metadata is used as req-opts:
        ;; {:keys [raw-bulk? thaw-opts dummy-reply :parse-exceptions?]}. We
        ;; could instead choose to split parsers and req metadata but bundling
        ;; the two is efficient + quite convenient in practice. Note that we
        ;; choose to _merge_ parser metadata during parser comp.
        req-opts (meta ?parser)
        unparsed-reply (if (contains? req-opts :dummy-reply) ; May be nil!
                         (:dummy-reply req-opts)
                         (get-unparsed-reply in req-opts))]
    (if-not ?parser unparsed-reply ; Common case
      (if (and (instance? Exception unparsed-reply)
               ;; Nb :parse-exceptions? is rare & not normally used by lib
               ;; consumers. Such parsers need to be written to _not_ interfere
               ;; with our ability to interpret Cluster error messages.
               (not (:parse-exceptions? req-opts)))
        unparsed-reply ; Return unparsed
        (try (?parser unparsed-reply)
             (catch Exception e
               (let [message (.getMessage e)]
                 (ex-info (format "Parser error: %s" message)
                   {:message message} e))))))))

;;;; Parsers

(defmacro parse
  "Wraps body so that replies to any wrapped Redis commands will be parsed with
  `(f reply)`. Replaces any current parser; removes parser when `f` is nil.
  See also `parser-comp`."
  [f & body] `(binding [*parser* ~f] ~@body))

(defn- comp-maybe [f g] (cond (and f g) (comp f g) f f g g :else nil))
(comment ((comp-maybe nil identity) :x))

(defn parser-comp "Composes parsers when f or g are nnil, preserving metadata."
  [f g] (let [m (merge (meta g) (meta f))]
          (with-meta (comp-maybe f g) m)))

;;; Special parsers used to communicate metadata to request enqueuer:
(defmacro parse-raw [& body] `(parse (with-meta identity {:raw-bulk? true}) ~@body))
(defmacro parse-nippy [thaw-opts & body]
  `(parse (with-meta identity {:thaw-opts ~thaw-opts}) ~@body))

(def return
  "Takes values and returns them as part of next reply from Redis server.
  Unlike `echo`, does not actually send any data to Redis."
  (let [return1
        (fn [context value]
          (swap! context
            (fn [[_ q]]
              [nil (conj q (with-meta [] ; Dummy request
                             {:parser (parser-comp *parser* ; Nb keep context's parser
                                        (with-meta identity {:dummy-reply value}))
                              :expected-keyslot nil ; Irrelevant
                              }))])))]
    (fn
      ([value] (return1 (:req-queue *context*) value))
      ([value & more]
       (encore/backport-run! (partial return1 (:req-queue *context*))
         (cons value more))))))

;;;; Requests

(def ^:const suppressed-reply-kw :carmine/suppressed-reply)
(defn-       suppressed-reply? [parsed-reply]
  (identical? parsed-reply suppressed-reply-kw))

(defn- return-parsed-reply "Implementation detail."
  [preply] (if (instance? Exception preply) (throw preply) preply))

(defn return-parsed-replies "Implementation detail."
  [preplies as-pipeline?]
  (let [nreplies (count preplies)]
    (if (or (> nreplies 1) as-pipeline?)
      preplies
      (let [pr1 (nth preplies 0 nil ; nb fallback for possible suppressed replies
                  )]
        (return-parsed-reply pr1)))))

(defn pull-requests "Implementation detail." [req-queue]
  (-> (swap! req-queue (fn [[_ q]] [q []])) (nth 0)))

(defn execute-requests*
  [conn requests get-replies? as-pipeline?]
  (let [nreqs (count requests)]
    (when (pos? nreqs)
      (let [{:keys [in out]} conn]
        (when-not (and in out) (throw no-context-ex))
        ;; (println "Sending requests: " requests)
        (send-requests out requests)
        (when get-replies?
          (if (or (> nreqs 1) as-pipeline?)
            (let [parsed-replies
                  (reduce
                   (fn [v-acc req-in]
                     (let [parsed-reply (get-parsed-reply in
                                                          (:parser (meta req-in)))]
                       (if (suppressed-reply? parsed-reply)
                         v-acc
                         (conj v-acc parsed-reply))))
                   []
                   requests)
                  nparsed-replies (count parsed-replies)]
              (return-parsed-replies parsed-replies as-pipeline?))
            (let [req (nth requests 0)
                  one-reply (get-parsed-reply in (:parser (meta req)))]
              (return-parsed-reply one-reply))))))))

(defn keyslot-index-reducer [result request]
  (let [[keyslots indexes] result
        i (count indexes)
        expected-keyslot (:expected-keyslot (meta request))
        j (count (get-in keyslots [expected-keyslot]))
        keyslots (update-in keyslots [expected-keyslot] conj request)
        indexes (assoc indexes i [expected-keyslot j] )]
    [keyslots indexes]))



(defn- read-futures [index request-futures]
  (loop [max (count index)
         i 0
         values []
         retries []]

    (if (< i max)
      (let [_ (println "read-futures" i)
            [keyslot j] (index i)

            request-results (deref (request-futures keyslot) 1000 :timeout)

            value (get request-results j)
            values (conj values value)
            retry? (= value :timeout)
            retries (if retry? (conj retries i) retries)]

        (recur max (inc i) values retries))

      [values retries])))

(declare create-futures)

(defn execute-requests-cluster*
  [conn requests get-replies? as-pipeline?]


  (loop [results requests
         retry-indexes (range (count requests))]

    (println "looping in execute for retry-indexes " retry-indexes)

    (let [selected-requests (map #(get requests %) retry-indexes)
          [keyslot-groups index] (reduce keyslot-index-reducer [] selected-requests)
          request-futures (create-futures keyslot-groups get-replies? as-pipeline?)
          [results retry-indexes] (read-futures index request-futures)]

      (println results "<--result")

      #_(if (empty? retry-indexes)
        (recur results retry-indexes)
        results))))

(defn execute-requests
  "Implementation detail.
  Sends given/dynamic requests to given/dynamic Redis server and optionally
  blocks to receive the relevant queued (pipelined) parsed replies."

  ;; For use with standard dynamic bindings:
  ([get-replies? as-pipeline?]
     (let [{:keys [conn req-queue]} *context*
           requests (pull-requests req-queue)]
       (execute-requests conn requests get-replies? as-pipeline?)))

  ;; For use with Cluster, etc.:
  ([conn requests get-replies? as-pipeline?]
     (if (get-in conn [:spec :cluster])
       (execute-requests-cluster* conn requests get-replies? as-pipeline?)
       (execute-requests* conn requests get-replies? as-pipeline?))))

;;;; General-purpose macros

(def ^:dynamic *nested-stashed-reqs*     nil)
(def ^:dynamic *nested-stash-consumed?_* nil)

(defn with-replies* "Implementation detail."
  [body-fn as-pipeline?]
  (let [{:keys [conn req-queue]} *context*
        ?nested-stashed-reqs     *nested-stashed-reqs*
        newly-stashed-reqs       (pull-requests req-queue)

        ;; We'll pass down the stash until the first stash consumer:
        stashed-reqs (if-let [nsr ?nested-stashed-reqs]
                       (into nsr newly-stashed-reqs)
                       newly-stashed-reqs)]

    (if (empty? stashed-reqs) ; Common case
      (let [_        (body-fn)
            new-reqs (pull-requests req-queue)]
        (execute-requests conn new-reqs :get-replies as-pipeline?))

      (let [nested-stash-consumed?_ (atom false)
            stash-size (count stashed-reqs)

            ?throwable ; Binding to support nested `with-replies` in body-fn:
            (binding [*nested-stashed-reqs*     stashed-reqs
                      *nested-stash-consumed?_* nested-stash-consumed?_]
              (try (body-fn) nil (catch Throwable t t)))

            new-reqs (pull-requests req-queue)
            all-reqs (if @nested-stash-consumed?_
                       new-reqs
                       (into stashed-reqs new-reqs))

            all-replies (execute-requests conn all-reqs :get-replies :as-pipeline)
            _           (when-let [nsc?_ *nested-stash-consumed?_*]
                          (reset! nsc?_ true))

            [stashed-replies requested-replies]
            [(subvec all-replies 0 stash-size)
             (subvec all-replies stash-size)]]

        ;; Restore any stashed replies to underlying stateful context:
        (parse nil ; We already parsed on stashing
          (encore/backport-run! return stashed-replies))

        (if ?throwable
          (throw ?throwable)
          (return-parsed-replies requested-replies as-pipeline?))))))

(defmacro with-replies
  "Alpha - subject to change.
  Evaluates body, immediately returning the server's response to any contained
  Redis commands (i.e. before enclosing context ends).

  As an implementation detail, stashes and then `return`s any replies already
  queued with Redis server: i.e. should be compatible with pipelining.

  Note on parsers: if you're writing a Redis command (e.g. a fn that is intended
  to execute w/in an implicit connection context) and you're using `with-replies`
  as an implementation detail (i.e. you're interpreting replies internally), you
  probably want `(parse nil (with-replies ...))` to keep external parsers from
  leaking into your internal logic."
  {:arglists '([:as-pipeline & body] [& body])}
  [& [s1 & sn :as sigs]]
  (let [as-pipeline? (identical? s1 :as-pipeline)
        body         (if as-pipeline? sn sigs)]
    `(with-replies* (fn [] ~@body) ~as-pipeline?)))

(defmacro with-context "Implementation detail."
  [conn & body]
  `(binding [*context* (Context. ~conn (atom [nil []]))
             *parser*  nil]
     ~@body))

(defmacro with-cached-spec [keyslot & body]
  `(if-let [spec# (@*keyslots-server-cache* ~keyslot)]
     (let [get-conn# (:get-conn *context*)
           release-conn# (:release-conn)
           conn# (get-conn# spec#)]
       (try
         (with-context conn#  ~@body)
         (finally
           (release-conn# conn#))))
     (do ~@body)))


(defn- create-futures [keyslot-groups get-replies? as-pipeline?]

  (reduce (fn [c [keyslot requests]]

            (let [a-future
                  (future (with-cached-spec keyslot
                            (execute-requests* (:conn *context*)
                                               (vec requests)
                                               get-replies?
                                               as-pipeline?)))]

              (assoc c keyslot a-future)))

          {} keyslot-groups))
