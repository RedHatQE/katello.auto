(ns kalpana.conf
  (:use [com.redhat.qe.config :only [property-map]])
  (:import [com.redhat.qe.auto.testng TestScript]))

;;config layer

(def kalpana-auto-properties {:server-url ["kalpana.url"]
                              :admin-user ["kalpana.admin.user" "acme_corporation_user"]
                              :admin-password ["kalpana.admin.password" "acme_corporation_user"]
                              :selenium-address ["selenium.address" "localhost:4444"]
                              :admin-org ["kalpana.admin.org" "ACME_Corporation"]
                              :sync-repo ["kalpana.sync.test.repo"]})

(def config (atom {}))

(defn init "initialize logging and read in properties"
  []
  (TestScript.)
  (swap! config merge (property-map kalpana-auto-properties)))

