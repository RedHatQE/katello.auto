(ns katello.tests.setup
  (:require [katello.trace :as tr]
            [katello.tasks]
            [katello.api-tasks]
            [test-clj.core :as test])
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
  (fn [] (binding [sel (new-selenium)
                  tr/tracer (tr/per-thread-tracer)]
          (tr/dotrace-all [katello.tasks katello.api-tasks]
                          [test/execute] []
                          (println "starting a sel")
                          (start-selenium)
                          (consume-fn)
                          (stop-selenium)))))

(def runner-config 
  {:thread-runner thread-runner
   :setup (fn [] (println "initializing.") (init))})
