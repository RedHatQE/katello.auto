(ns katello.conf
  (:require [clojure.java.io :as io])
  (:import [java.io PushbackReader]
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

(defn init
  "Read in properties and set some defaults. This function should be
   called before selenium client is created or any tests are run."
  [config-file]
  ;;bid adeiu to j.u.l logging
  (-> (Logger/getLogger "") (.setLevel Level/OFF))
  
  (when config-file 
    (let [user-config (with-open [r (io/reader config-file)]
                        (read (PushbackReader. r)))]
      (swap! config merge user-config)))
  
  (def ^:dynamic *session-user* (@config :admin-user))
  (def ^:dynamic *session-password* (@config :admin-password))
  (when (@config :clients)
    (def ^:dynamic *clients* (@config :clients)))
  (def ^:dynamic *browsers* (@config :selenium-browsers))
  (def ^:dynamic *environments* (@config :environments))) 



