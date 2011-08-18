(ns katello.tests.setup
  (:require [katello.trace :as tr]
            [katello.tasks]
            [katello.api-tasks]
            [test.tree :as test])
  (:use [katello.conf :only [init config]]
        [katello.tasks :only [login]]
        [clojure.contrib.string :only [split]]
        [com.redhat.qe.auto.selenium.selenium :only [connect new-sel browser sel]]))

(defn new-selenium [& [single-thread]]
  (let [sel-addr (@config :selenium-address)
        [host port] (split #":" sel-addr)
        sel-fn (if single-thread
                 connect
                 new-sel)] 
    (sel-fn host (Integer/parseInt port) "" (@config :server-url))))

(defn start-selenium []  
  (browser start)
  (browser open (@config :server-url))
  (login (@config :admin-user) (@config :admin-password)))

(defn stop-selenium []
  (browser stop))

(defn thread-runner [consume-fn]
  (fn [] (binding [sel (new-selenium)
                  tr/tracer (tr/per-thread-tracer)]
          (tr/dotrace-all {:namespaces [katello.tasks katello.api-tasks]
                           :fns [test/execute]}
                          (println "starting a sel")
                          (start-selenium)
                          (consume-fn)
                          (stop-selenium)))))

(def runner-config 
  {:thread-runner thread-runner
   :setup (fn [] (println "initializing.") (init))})
