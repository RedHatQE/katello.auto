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
                              :sync-repo ["katello.sync.test.repo"]})

(def config (atom {}))

(defn init "initialize logging and read in properties"
  []
  (TestScript/loadProperties)
  (-> (Logger/getLogger "") (.setLevel Level/OFF))
  (swap! config merge (property-map katello-auto-properties)))

