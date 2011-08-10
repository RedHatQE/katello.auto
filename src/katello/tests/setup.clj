(ns katello.tests.setup
  (:use [katello.conf :only [init config]]
        [katello.tasks :only [login]]
        [clojure.contrib.string :only [split]]
        [com.redhat.qe.auto.selenium.selenium :only [connect new-sel browser sel]]))

(defn new-selenium []
  (let [sel-addr (@config :selenium-address)
        [host port] (split #":" sel-addr)] 
    (new-sel host (Integer/parseInt port) "" (@config :server-url))))

(defn start-selenium []  
  (browser start)
  (browser open (@config :server-url))
  (login (@config :admin-user) (@config :admin-password)))

(defn stop-selenium []
  (browser stop))

(def runner-config 
  {:binding-map {#'sel new-selenium}
   :setup (fn [] (println "initializing.") (init))
   :thread-setup (fn [] (println "starting a sel") (start-selenium))
   :thread-teardown (fn [] (stop-selenium))})
