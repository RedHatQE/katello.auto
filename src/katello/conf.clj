(ns katello.conf
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            clojure.tools.cli
            selenium-server
            [fn.trace :refer [all-fns]]
            [deltacloud :as cloud]
            katello
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
   
   ["-m" "--fake-manifest-url" "URL that points to a fake test manifest"
    ;;:default "http://github.com/iNecas/katello-cli/raw/fake-manifests-signed/system-test/fake-manifest-syncable.zip"]
    :default "http://cosmos.lab.eng.pnq.redhat.com/rhel64/fake-manifest-syncable.zip"]

   ["-r" "--fake-repo-url" "A Fake content delivery url to be used with --fake-manifest-url"
    :default "http://hudson.rhq.lab.eng.bos.redhat.com/cds/"]
   
   ["--redhat-manifest-url" "URL that points to a Red Hat test manifest"
    :default "http://cosmos.lab.eng.pnq.redhat.com/rhel64/redhat-manifest.zip"]

   ["--redhat-repo-url" "A Red Hat content delivery url to be used with --redhat-manifest-url"
    :default "https://cdn.redhat.com/"]
   
   ["--key-url" "A private key used to sign the cloned manifests"
    ;;:default "https://raw.github.com/iNecas/katello-misc/sign_manifest.sh/scripts/test/manifest_generation/fake_key.pem"]
    :default "http://cosmos.lab.eng.pnq.redhat.com/rhel64/fake_key.pem"]

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
    :parse-fn #(->> (string/split % #",") (map symbol) (into #{}))]
 
   ["--sethostname" "URL of a script which can set the hostname of newly created VM"
    :default "https://raw.github.com/RedHatQE/jenkins-scripts/master/jenkins/sethostname.sh"]
   
   ["--agent-repo" "URL of a .repo file to point to where katello-agent can be installed from."
    :default "https://raw.github.com/gist/1978881"]
   
   ["--gpg-key" "URL of a GPG-Key"
    :default "http://inecas.fedorapeople.org/fakerepos/zoo/RPM-GPG-KEY-dummy-packages-generator"]])

(def defaults (first (apply clojure.tools.cli/cli [] options)))

(def config (atom {}))

;; Tracing setup

(def ^{:doc "Some pre-set trace settings. Don't trace too deeply into some
  functions (or not at all into others)"}
  trace-depths
  '{com.redhat.qe.auto.selenium.selenium/call-sel 1
    katello.menu/fmap 0
    katello.ui/component-deployment-dispatch 0
    katello.ui/current-session-deployment 0
    katello.notifications/success? 0
    katello.tasks/uniqueify 0
    katello.tasks/uniques 0
    katello.conf/client-defs 0
    katello.rest/read-json-safe 0
    katello.rest/get-id 1
    katello/chain 1
    katello/instance-or-nil? 0
    })

(defn record-contructor-depths
  "Returns trace setting to not trace record constructors."
  []
  (zipmap (filter (fn [fsym]
             (re-find #"/map->|/new" (str fsym)))
                  (all-fns '(katello)))
          (repeat 0)))

(defn trace-list
  "Creates a list of functions to trace. Includes all katello
   namespaces (except a few functions), and some of the API and
   underlying lib namespaces."
  []
  (-> (->> (loaded-libs)
           (filter (fn [sym] (-> sym str (.startsWith "katello"))))
           all-fns
           (concat '(com.redhat.qe.auto.selenium.selenium/call-sel
                     clj-http.client/get
                     clj-http.client/put
                     clj-http.client/post
                     clj-http.client/delete)))
      (zipmap (repeat nil)) ;; default no limit to trace depth
      (merge trace-depths (record-contructor-depths))))
 

(declare ^:dynamic *session-user*
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
  
  (def ^:dynamic *session-user* (katello/newUser {:name (@config :admin-user)
                                                  :password (@config :admin-password)
                                                  :email "admin@katello.org"}))
  (def ^:dynamic *session-org* (katello/newOrganization {:name (@config :admin-org)}))
  (def ^:dynamic *cloud-conn* (when-let [dc-url (@config :deltacloud-url)]
                                (deltacloud.Connection. dc-url           
                                                        (@config :deltacloud-user)
                                                        (@config :deltacloud-password))))
  (def ^:dynamic *browsers* (@config :browser-types))
  (def ^:dynamic *environments* (for [e (@config :environments)]
                                  (katello/newEnvironment {:name e
                                                           :org *session-org*})))) 

(def promotion-deletion-lock nil) ;; var to lock on for promotions

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
            :hwp_memory "512" ;;override to allow RHEL6.3 stock to boot - 256 is too small?
            :image_id (@config :deltacloud-image-id)})))

(defmacro with-creds
  "Execute body and with the given user and password, all api calls
   will use these creds.  No explicit logging in/out is done in the
   UI."
  [user password & body]
  `(binding [*session-user* ~user]
     ~@body))

(defmacro with-org
  "Binds *session-org* to a new value within body, all api calls will
   use this org. Does not switch the org in the UI - see
   katello.organizations/switch for that."
  [org-name & body]
   `(binding [*session-org* ~org-name]
      ~@body))


