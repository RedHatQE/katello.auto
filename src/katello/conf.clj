(ns katello.conf
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            clojure.tools.cli
            selenium-server)
  
  (:import [java.io PushbackReader FileNotFoundException]
           [java.util.logging Level Logger]))

;;config layer


(def options
  [["-h" "--help" "Print usage guide"
    :default false :flag true]

   ["-s" "--server-url" "URL of the Katello server to test.  Should use https URL if https is enabled."]
   
   ["-u" "--admin-user" "The admin username of the Katello server"
    :default "admin"]

   ["-p" "--admin-password" "The admin password of the Katello server"
    :default "admin"]

   ["-o" "--admin-org" "Name of Katello's admin organization"
    :default "ACME_Corporation"]

   ["-y" "--sync-repo" "The url for a test repo to sync"
    :default "http://download.englab.brq.redhat.com/scratch/inecas/fakerepos/cds/content/nature/6Server/x86_64/rpms/"]
   ["-m" "--redhat-manifest-url" "URL that points to a Red Hat test manifest"
    :default "http://inecas.fedorapeople.org/fakerepos/cds/fake-manifest-syncable.zip"]

   ["-e" "--environments" "A comma separated list of environment names to test with (need not already exist)"
    :parse-fn #(seq (string/split % #",")) :default '("Development" "Q-eh") ]

   ["--clients" "A comma separated list of client machines to use for end to end testing must specify at least one per thread"
    :parse-fn #(string/split % #",")]

   ["-a" "--selenium-address" "Address of the selenium server to connect to. eg 'host.com:4444' If none specified, an embedded selenium server is used."]

   ["-k" "--client-ssh-key" "The location of a (passwordless) ssh private key that can be used to access client machines."
    :default (format "%s/.ssh/id_auto_dsa" (System/getProperty "user.home"))]
   
   ["-n" "--num-threads" "Number of threads to run tests with"
    :parse-fn #(Integer. %) :default 3]

   ["-b" "--browser-types" "Selenium browser types, eg '*firefox' or '*firefox,*googlechrome' (multiple values only used when threads > 1"
    :default ["*firefox"] :parse-fn #(string/split % #",")]


   ["-c" "--config" "Config files (containing a clojure map of config options) to read and overlay  other command line options on top of - a list of comma separated places to look - first existing file is used and rest are ignored."
    :default ["automation-properties.clj" (format "%s/automation-properties.clj" (System/getProperty "user.home"))]
    :parse-fn #(string/split % #",")]])

(def defaults (first (apply clojure.tools.cli/cli [] options)))

(def config (atom {}))


(declare ^:dynamic *session-user*
         ^:dynamic *session-password*
         ^:dynamic *browsers*
         ^:dynamic *environments*)

(def ^:dynamic *clients* nil)

(defn- try-read-configs
  "try to read a config from filename, if file doesn't exist, return nil"
  [filenames]
  (for [f filenames]
    (try
      (with-open [r (io/reader f)]
        (read (PushbackReader. r)))
      (catch FileNotFoundException fnfe
        nil))) )

(defn init
  "Read in properties and set some defaults. This function should be
   called before selenium client is created or any tests are run."
  [opts]
  ;;bid adeiu to j.u.l logging
  (-> (Logger/getLogger "") (.setLevel Level/OFF))
  
  (swap! config merge defaults opts)
  (swap! config merge (->> (:config @config)
                         try-read-configs
                         (drop-while nil?)
                         first))
  (swap! config merge opts) ; merge 2nd time to override anything in
                            ; config files

  ;; if user didn't specify sel address, start a server and use that
  ;; address.
  (when-not (@config :selenium-address)
    (selenium-server/start)
    (swap! config assoc :selenium-address "localhost:4444"))
  
  (def ^:dynamic *session-user* (@config :admin-user))
  (def ^:dynamic *session-password* (@config :admin-password))
  (when (@config :clients)
    (def ^:dynamic *clients* (@config :clients)))
  (def ^:dynamic *browsers* (@config :browser-types))
  (def ^:dynamic *environments* (@config :environments))) 



