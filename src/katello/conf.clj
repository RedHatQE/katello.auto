(ns katello.conf
  (:use [com.redhat.qe.config :only [property-map]]
        [clojure.string :only [split]])
  (:import [com.redhat.qe.auto.testng TestScript]
           [java.util.logging Logger Level]))

;;config layer

(def katello-auto-properties {:server-url ["katello.url"]
                              :admin-user ["katello.admin.user" "admin"]
                              :admin-password ["katello.admin.password" "admin"]
                              :owner-user ["katello.owner.user" "acme_corporation_user"]
                              :owner-password ["katello.owner.password" "acme_corporation_user"]
                              :selenium-address ["selenium.address" "localhost:4444"]
                              :admin-org ["katello.admin.org" "ACME_Corporation"]
                              :sync-repo ["katello.sync.test.repo" "http://hudson.rhq.lab.eng.bos.redhat.com:8080/hudson/job/subscription-manager_master.el6/lastSuccessfulBuild/artifact/rpms/x86_64"]
                              :redhat-repo-url ["katello.redhat.repo.url" "https://sat-perf-03.idm.lab.bos.redhat.com/pulp/repos/"]
                              :redhat-manifest-url ["katello.redhat.manifest.url" "http://hudson.rhq.lab.eng.bos.redhat.com:8080/shared/systemengine/manifests/manifest-qe-20111207.zip"]
                              :first-env ["katello.environments.first" "Development"]
                              :second-env ["katello.environments.second" "Q-eh"]
                              :clients ["katello.clients"]
                              :client-ssh-key ["sm.sshkey.private" (format "%s/.ssh/id_auto_dsa"
                                                                           (System/getProperty "user.home"))]
                              :client-ssh-key-passphrase ["sm.sshkey.passphrase"]})

(def config (atom {}))

(declare ^:dynamic *session-user*
         ^:dynamic *session-password*)

(def ^:dynamic *clients* nil)

(defn init "initialize logging and read in properties"
  []
  (TestScript/loadProperties)
  (-> (Logger/getLogger "") (.setLevel Level/OFF))
  (swap! config merge (property-map katello-auto-properties))
  (def ^:dynamic *session-user* (@config :admin-user))
  (def ^:dynamic *session-password* (@config :admin-password))
  (when (@config :clients)
    (def ^:dynamic *clients* (split (@config :clients) #","))))


