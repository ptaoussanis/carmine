(defproject com.taoensso/carmine "3.0.0-SNAPSHOT"
  :description "Clojure Redis client & message queue"
  :url "https://github.com/ptaoussanis/carmine"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure         "1.4.0"]
                 [org.clojure/tools.macro     "0.1.5"]
                 [commons-pool/commons-pool   "1.6"]
                 [commons-codec/commons-codec "1.9"]
                 [org.clojure/data.json       "0.2.4"]
                 [com.taoensso/timbre         "3.0.0"]
                 [com.taoensso/nippy          "2.6.0-alpha3"]]
  :profiles {:1.4   {:dependencies [[org.clojure/clojure  "1.4.0"]]}
             :1.5   {:dependencies [[org.clojure/clojure  "1.5.1"]]}
             :1.6   {:dependencies [[org.clojure/clojure  "1.6.0-beta1"]]}
             :dev   {:dependencies [[ring/ring-core       "1.2.1"]
                                    [com.taoensso/faraday "1.0.2"]]}
             :test  {:dependencies [[expectations         "1.4.56"]
                                    [clj-aws-s3           "0.3.8"]]}
             :bench {:dependencies [] :jvm-opts ["-server"]}}
  :aliases {"test-all"    ["with-profile" "+test,+1.4:+test,+1.5:+test,+1.6"
                           "do" "test," "expectations"]
            "test-auto"   ["with-profile" "+test" "autoexpect"]
            "start-dev"   ["with-profile" "+dev,+test,+bench" "repl" ":headless"]
            "start-bench" ["trampoline" "start-dev"]
            "codox"       ["with-profile" "+test,+1.5" "doc"]}
  :plugins [[lein-expectations "0.0.8"]
            [lein-autoexpect   "1.2.1"]
            [lein-ancient      "0.5.4"]
            [codox             "0.6.6"]]
  :min-lein-version "2.0.0"
  :global-vars {*warn-on-reflection* true}
  :repositories
  {"sonatype"
   {:url "http://oss.sonatype.org/content/repositories/releases"
    :snapshots false
    :releases {:checksum :fail}}
   "sonatype-snapshots"
   {:url "http://oss.sonatype.org/content/repositories/snapshots"
    :snapshots true
    :releases {:checksum :fail :update :always}}})
