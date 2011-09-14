(ns katello.conf
  (:use [com.redhat.qe.config :only [property-map]])
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
                              :sync-repo ["katello.sync.test.repo"]
                              :redhat-repo-url ["katello.redhat.repo.url" "https://sat-perf-03.idm.lab.bos.redhat.com/pulp/repos/"]
                              :redhat-manifest-url ["katello.redhat.manifest.url" "http://axiom.rdu.redhat.com/git/gitweb.cgi?p=system-engine;a=blob;f=scripts/test/sample-candlepin-export.zip;h=4451ff79098c1734c2a465e09a0c42fbc6256ae7;hb=HEAD"]})

(def config (atom {}))

(declare *session-user* *session-password*)

(defn init "initialize logging and read in properties"
  []
  (TestScript/loadProperties)
  (-> (Logger/getLogger "") (.setLevel Level/OFF))
  (swap! config merge (property-map katello-auto-properties))
  (def *session-user* (@config :admin-user))
  (def *session-password* (@config :admin-password)))


