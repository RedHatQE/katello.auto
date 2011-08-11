(ns katello.tests.setup
  (:require [katello.trace :as tr]
            [katello.tasks]
            [katello.api-tasks])
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

(defn thread-runner [consume-fn]
  (fn []
    (binding
        [sel (new-selenium)
         tr/tracer (let [thread-name (.getName (Thread/currentThread))]
                                (fn [name value]
                                  (let [s (str (when name (format "%6s:" name))  value "\n")]
                                    (spit (str thread-name ".trace") s
                                          :append true))))]
      (tr/dotrace-all [katello.tasks katello.api-tasks]
                   [] []
               (println "starting a sel")
               (start-selenium)
               (consume-fn)
               (stop-selenium)))))

(def runner-config 
  {:thread-runner thread-runner
   :binding-map {#'sel new-selenium
                 #'katello.trace/tracer (fn [] (let [thread-name (.getName (Thread/currentThread))]
                                                (fn [name value]
                                                  (let [s (str (when name (format "%6s:" name))  value "\n")]
                                                    (spit (str thread-name ".trace") s
                                                          :append true)))))}
   :setup (fn [] (println "initializing.") (init))
   :thread-setup (fn [] (println "starting a sel") (start-selenium))
   :thread-teardown (fn [] (stop-selenium))})
