(ns katello.tests.setup
  (:use [katello.conf :only [init config]]
        [com.redhat.qe.auto.selenium.selenium :only [connect browser]]))

(defn start-sel []
  (init)
  (let [sel-addr (@config :selenium-address)
        [host port] (split #":" sel-addr)] 
    (connect host (Integer/parseInt port) "" (@config :server-url))
    (browser start)
    (browser open (@config :server-url))))

(defn stop-selenium []
  (browser stop))


