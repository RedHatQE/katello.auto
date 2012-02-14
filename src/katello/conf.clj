(ns katello.conf
  (:use [com.redhat.qe.config :only [property-map]]
        [clojure.string :only [split]])
  (:import [com.redhat.qe.auto.testng TestScript]
           [java.util.logging Logger Level]))

;;config layer
;;
;;mapping of configuration keys to java properties that may have been
;;read in or passed in on the command line.  last optional item is a
;;default if the property was not set.
(def katello-auto-properties {:server-url ["katello.url"]
                              :admin-user ["katello.admin.user" "admin"]
                              :admin-password ["katello.admin.password" "admin"]
                              :owner-user ["katello.owner.user" "acme_corporation_user"]
                              :owner-password ["katello.owner.password" "acme_corporation_user"]
                              :selenium-address ["selenium.address" "localhost:4444"]
                              :selenium-browsers ["selenium.browsers" "*firefox"]
                              :threads ["test.tree.threads" "3"]
                              :admin-org ["katello.admin.org" "ACME_Corporation"]
                              :sync-repo ["katello.sync.test.repo" "http://hudson.rhq.lab.eng.bos.redhat.com:8080/hudson/job/subscription-manager_master.el6/lastSuccessfulBuild/artifact/rpms/x86_64"] 
                              :redhat-repo-url ["katello.redhat.repo.url" "http://inecas.fedorapeople.org/fakerepos/cds/"]
                              :redhat-manifest-url ["katello.redhat.manifest.url" "http://inecas.fedorapeople.org/fakerepos/cds/fake-manifest-syncable.zip"]
                              :first-env ["katello.environments.first" "Development"]
                              :second-env ["katello.environments.second" "Q-eh"]
                              :clients ["katello.clients"]
                              :client-ssh-key ["sm.sshkey.private" (format "%s/.ssh/id_auto_dsa"
                                                                           (System/getProperty "user.home"))]
                              :client-ssh-key-passphrase ["sm.sshkey.passphrase"]})

(def config (atom {}))

(declare ^:dynamic *session-user*
         ^:dynamic *session-password*
         ^:dynamic *browsers)

(def ^:dynamic *clients* nil)

(defn init
  "Read in properties and set some defaults. This function should be
   called before selenium client is created or any tests are run."
  []
  (TestScript/loadProperties)
  (-> (Logger/getLogger "") (.setLevel Level/OFF))
  (swap! config merge (property-map katello-auto-properties))
  (def ^:dynamic *session-user* (@config :admin-user))
  (def ^:dynamic *session-password* (@config :admin-password))
  (when (@config :clients)
    (def ^:dynamic *clients* (split (@config :clients) #",")))
  (def ^:dynamic *browsers* (split (@config :selenium-browsers) #",")))


