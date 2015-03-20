(defproject com.taoensso/carmine "2.9.0"
  :author "Peter Taoussanis <https://www.taoensso.com>"
  :description "Clojure Redis client & message queue"
  :url "https://github.com/ptaoussanis/carmine"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "Same as Clojure"}
  :min-lein-version "2.3.3"
  :global-vars {*warn-on-reflection* true
                *assert* true}

  :dependencies
  [[org.clojure/clojure              "1.4.0"]
   [com.taoensso/encore              "1.21.0"]
   [com.taoensso/timbre              "3.3.1"]
   [com.taoensso/nippy               "2.7.1"]
   [org.apache.commons/commons-pool2 "2.2"]
   [commons-codec/commons-codec      "1.10"]
   [org.clojure/data.json            "0.2.5"]]

  :profiles
  {;; :default [:base :system :user :provided :dev]
   :server-jvm {:jvm-opts ^:replace ["-server"]}
   :1.5  {:dependencies [[org.clojure/clojure     "1.5.1"]]}
   :1.6  {:dependencies [[org.clojure/clojure     "1.6.0"]]}
   :test {:dependencies [[expectations            "2.0.13"]
                         [org.clojure/test.check  "0.6.1"]
                         [com.taoensso/faraday    "1.5.0"]
                         [clj-aws-s3              "0.3.10"]
                         [ring/ring-core          "1.3.2"]]
          :plugins [[lein-expectations "0.0.8"]
                    [lein-autoexpect   "1.4.0"]]}
   :dev
   [:1.6 :test
    {:plugins [[lein-ancient "0.5.4"]
               [codox        "0.8.10"]]}]}

  :test-paths ["test" "src"]

  :aliases
  {"test-all"   ["with-profile" "default:+1.5:+1.6" "expectations"]
   ;; "test-all"   ["with-profile" "default:+1.6" "expectations"]
   "test-auto"  ["with-profile" "+test" "autoexpect"]
   "deploy-lib" ["do" "deploy" "clojars," "install"]
   "start-dev"  ["with-profile" "+server-jvm" "repl" ":headless"]}

  :repositories {"sonatype-oss-public"
                 "https://oss.sonatype.org/content/groups/public/"})
