(ns katello.tests.setup
  (:require [fn.trace :as tr]
            (katello [tasks :as tasks]
                     [conf :as conf]
                     [api-tasks :as api-tasks]) 
            [test.tree :as test])
  (:use [clojure.string :only [split]]
        [com.redhat.qe.auto.selenium.selenium :only [connect new-sel browser sel jquery-ajax-finished]]
        [com.redhat.qe.verify :only [check]])
  )

(defn new-selenium [& [single-thread]]
  (let [sel-addr (@conf/config :selenium-address)
        [host port] (split sel-addr #":")
        sel-fn (if single-thread
                 connect
                 new-sel)] 
    (sel-fn host (Integer/parseInt port) "" (@conf/config :server-url))))

(defn start-selenium []  
  (browser start)
  (browser open (@conf/config :server-url) jquery-ajax-finished)
  (tasks/login (@conf/config :admin-user) (@conf/config :admin-password)))

(defn switch-new-admin-user [user pw]
  (tasks/create-user user {:password pw })
  (tasks/assign-role {:user user
                      :roles ["Administrator"]})
  (tasks/logout)
  (tasks/login user pw))

(defn stop-selenium []
  (browser stop))

(defn thread-runner [consume-fn]
  (fn [] (binding [sel (new-selenium)
                  tr/tracer (tr/per-thread-tracer tr/clj-format)
                  katello.conf/*session-user* (tasks/uniqueify
                                               (str (@conf/config :admin-user)
                                                    (->> (Thread/currentThread)
                                                         .getName
                                                         (re-seq #"\d+")
                                                         first)))]
          (tr/dotrace-all {:namespaces [katello.tasks katello.api-tasks]
                           :fns [test/execute
                                 start-selenium stop-selenium switch-new-admin-user
                                 check
                                 com.redhat.qe.auto.selenium.selenium/call-sel
                                 ;com.redhat.qe.auto.selenium.selenium/locator-args
                                 com.redhat.qe.auto.selenium.selenium/fill-item]
                           :exclude [katello.tasks/notification
                                     katello.tasks/clear-all-notifications
                                     katello.tasks/success?]}
                          (println "starting a selenium session.")
                          (try (start-selenium)
                               (switch-new-admin-user conf/*session-user* conf/*session-password*)
                               (catch Exception e (.printStackTrace e)))
                          (consume-fn)
                          (stop-selenium)
                          (tr/htmlify "html" [(str (.getName (Thread/currentThread)) ".trace")]
                                      "http://hudson.rhq.lab.eng.bos.redhat.com:8080/shared/syntaxhighlighter/")))))

(def runner-config 
  {:thread-runner thread-runner
   :setup (fn [] (println "initializing.") (conf/init))})
