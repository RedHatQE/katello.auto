(ns katello.conf
  (:require [clojure.java.io :as io])
  (:import [java.io PushbackReader FileNotFoundException]
           [java.util.logging Level Logger]))

;;config layer
(def defaults
  {:admin-user  "admin"
   :admin-password  "admin"
   :selenium-address  "localhost:4444"
   :selenium-browsers  ["*firefox"]
   :admin-org  "ACME_Corporation"
   :sync-repo  "http://download.englab.brq.redhat.com/scratch/inecas/fakerepos/cds/content/nature/6Server/x86_64/rpms/" 
   :redhat-repo-url  "http://download.englab.brq.redhat.com/scratch/inecas/fakerepos/cds/"
   :redhat-manifest-url  "http://inecas.fedorapeople.org/fakerepos/cds/fake-manifest-syncable.zip"
   :environments  '("Development" "Q-eh") ;;list makes it easier to conj on library at the beginning
   :client-ssh-key  (format "%s/.ssh/id_auto_dsa"
                            (System/getProperty "user.home"))})

(def config (atom defaults))


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
  ([] (init ["automation-properties.clj"
             (format "%s/automation-properties.clj" (System/getProperty "user.home"))]))
  ([config-files]
     ;;bid adeiu to j.u.l logging
     (-> (Logger/getLogger "") (.setLevel Level/OFF))
     
     (when config-files
       (swap! config merge  (->> config-files
                               try-read-configs
                               (drop-while nil?)
                               first)))
  
     (def ^:dynamic *session-user* (@config :admin-user))
     (def ^:dynamic *session-password* (@config :admin-password))
     (when (@config :clients)
       (def ^:dynamic *clients* (@config :clients)))
     (def ^:dynamic *browsers* (@config :selenium-browsers))
     (def ^:dynamic *environments* (@config :environments)))) 



