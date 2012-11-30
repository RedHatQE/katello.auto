(ns katello.conf
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            clojure.tools.cli
            selenium-server
            [fn.trace :refer [all-fns]]
            [deltacloud :as cloud]
            [katello.tasks :refer [unique-names]])
  
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
    :default "http://hudson.rhq.lab.eng.bos.redhat.com/cds/content/nature/1.0/x86_64/rpms/"]
   
   ["-m" "--redhat-manifest-url" "URL that points to a Red Hat test manifest"
    :default "http://inecas.fedorapeople.org/fakerepos/cds/fake-manifest-syncable.zip"]

   ["-r" "--redhat-repo-url" "A Red Hat content delivery url to be used with --redhat-manifest-url"
    :default "http://hudson.rhq.lab.eng.bos.redhat.com/cds/"]

   ["-e" "--environments" "A comma separated list of environment names to test with (need not already exist)"
    :parse-fn #(seq (string/split % #",")) :default '("Development" "Q-eh") ]

   ["--deltacloud-url" "A URL to deltacloud API that can be used to provision client machines for tests that require them"]

   ["--deltacloud-user" "The username to log in to deltacloud api."]

   ["--deltacloud-password" "The password for the deltacloud-user."]

   ["--deltacloud-image-id" "The image id to use to provision clients."]

   ["-a" "--selenium-address" "Address of the selenium server to connect to. eg 'host.com:4444' If none specified, an embedded selenium server is used."]

   ["-k" "--client-ssh-key" "The location of a (passwordless) ssh private key that can be used to access client machines."
    :default (format "%s/.ssh/id_auto_dsa" (System/getProperty "user.home"))]
   
   ["-n" "--num-threads" "Number of threads to run tests with"
    :parse-fn #(Integer. %) :default 3]

   ["-b" "--browser-types" "Selenium browser types, eg '*firefox' or '*firefox,*googlechrome' (multiple values only used when threads > 1"
    :default ["*firefox"] :parse-fn #(string/split % #",")]

   ["--locale" "A locale to set the browser to for all the tests (if not set, will default to browser's default.  Firefox only. eg 'fr' for french. Note, if using a remote selenium server, that server must already have a profile set up where the profile name equals the locale name."]
   
   ["-c" "--config" "Config files (containing a clojure map of config options) to read and overlay  other command line options on top of - a list of comma separated places to look - first existing file is used and rest are ignored."
    :default ["automation-properties.clj" (format "%s/automation-properties.clj" (System/getProperty "user.home"))]
    :parse-fn #(string/split % #",")]


   #_("to regenerate the list of test namespaces to trace:"
      (require 'katello.tests.suite)
      (filter (fn [sym] (-> sym str (.startsWith "katello.tests"))) (loaded-libs)))
   
   ["--trace" "Namespaces and functions to trace"
    :parse-fn #(->> (string/split % #",") (map symbol) vec)]
   
   ["--trace-excludes" "Functions to exclude from tracing"
    :parse-fn #(->> (string/split % #",") (map symbol) (into #{}))]])

(def defaults (first (apply clojure.tools.cli/cli [] options)))

(def config (atom {}))

(defn trace-list
  "Creates a list of functions to trace. Includes all katello
   namespaces (except a few functions), and some of the API and
   underlying lib namespaces."
  []
  (->> (loaded-libs)
     (filter (fn [sym] (-> sym str (.startsWith "katello"))))
     (concat '(katello.client.provision))
     all-fns
     (concat '(com.redhat.qe.auto.selenium.selenium/call-sel
               clj-http.client/get
               clj-http.client/put
               clj-http.client/post
               clj-http.client/delete)) ;;extra fns to add
     
     (remove #{'katello.notifications/success? ;;fns to remove
               'katello.tasks/uniqueify
               'katello.tasks/unique-format
               'katello.tasks/unique-names
               'katello.tasks/uniques-formatted
               'katello.tasks/uniqueify-vals
               'katello.tasks/timestamps
               'katello.tasks/date-string
               'katello.tasks/timestamped-seq
               'katello.conf/client-defs})))


(declare ^:dynamic *session-user*
         ^:dynamic *session-password*
         ^:dynamic *session-org*
         ^:dynamic *browsers*
         ^:dynamic *cloud-conn*
         ^:dynamic *environments*)

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
  (let [non-defaults (into {}
                           (filter (fn [[k v]] (not= v (k defaults)))
                                   opts))]
    (swap! config merge non-defaults)) ; merge 2nd time to override anything in
                                       ; config files

  ;; if user didn't specify sel address, start a server and use that
  ;; address.
  (when-not (@config :selenium-address)
    (selenium-server/start)
    (swap! config assoc :selenium-address "localhost:4444"))
  
  (def ^:dynamic *session-user* (@config :admin-user))
  (def ^:dynamic *session-password* (@config :admin-password))
  (def ^:dynamic *session-org* (@config :admin-org))
  (def ^:dynamic *cloud-conn* (when-let [dc-url (@config :deltacloud-url)]
                                (cloud/connection dc-url           
                                                  (@config :deltacloud-user)
                                                  (@config :deltacloud-password))))
  (def ^:dynamic *browsers* (@config :browser-types))
  (def ^:dynamic *environments* (@config :environments))) 


(defn no-clients-defined "Blocks a test if no client machines are accessible." [_]
  (try
    (cloud/instances *cloud-conn*)
    []
    (catch Exception e [e])))

(defn client-defs "Return an infinite seq of client instance property definitions."
  [basename]
  (for [instname (unique-names basename)]
    (merge cloud/small-instance-properties
           {:name instname
            :image_id (@config :deltacloud-image-id)})))

(defmacro with-creds
  "Execute body and with the given user and password, all api calls
   will use these creds.  No explicit logging in/out is done in the
   UI."
  [user password & body]
  `(binding [*session-user* ~user
             *session-password* ~password]
     ~@body))

(defmacro with-org
  "Binds *session-org* to a new value within body, all api calls will
   use this org. Does not switch the org in the UI - see
   katello.organizations/switch for that."
  [org-name & body]
   `(binding [*session-org* ~org-name]
      ~@body))


