(ns katello.tests.setup
  (:refer-clojure :exclude [replace])
  (:require [clojure.data :as data]
            (katello [tasks :as tasks]
                     [api-tasks :as api]
                     [client :as client]) 
            [test.tree :as test]
            [test.tree.watcher :as watch])
  (:use [clojure.string :only [split replace]]
        [katello.conf]
        [fn.trace]
        [com.redhat.qe.auto.selenium.selenium :only [connect new-sel browser sel jquery-ajax-finished]]
        [com.redhat.qe.verify :only [check]]))

(defn new-selenium [& [single-thread]]
  (let [[host port] (split (@config :selenium-address) #":")
        sel-fn (if single-thread connect new-sel)] 
    (sel-fn host (Integer/parseInt port) "" (@config :server-url))))

(defn start-selenium []  
  (browser start)
  (browser open (@config :server-url) jquery-ajax-finished)
  (tasks/login (@config :admin-user) (@config :admin-password)))

(defn switch-new-admin-user [user pw]
  (api/with-creds (@config :admin-user) (@config :admin-password)
    (api/create-user user {:password pw
                           :email (str user "@myorg.org")}))
  (tasks/assign-role {:user user
                      :roles ["Administrator"]})
  (tasks/logout)
  (tasks/login user pw))

(defn stop-selenium []
  (browser stop))

(defn thread-runner [consume-fn]
  (fn []
    (let [thread-number (->> (Thread/currentThread) .getName (re-seq #"\d+") first Integer.)]
      (binding [sel (new-selenium)
                tracer (per-thread-tracer clj-format)
                *session-user* (tasks/uniqueify
                                             (str (@config :admin-user)
                                                  thread-number))
                client/*runner* (client/new-runner (nth *clients* thread-number)
                                                   "root" nil
                                                   (@config :client-ssh-key)
                                                   (@config :client-ssh-key-passphrase)) ]
        (dotrace-all
         {:namespaces [katello.tasks katello.api-tasks katello.client]
          :fns [test/execute
                start-selenium stop-selenium switch-new-admin-user
                check
                com.redhat.qe.auto.selenium.selenium/call-sel]
          :exclude [katello.tasks/notification
                    katello.tasks/clear-all-notifications
                    katello.tasks/success?
                    katello.tasks/uniqueify
                    katello.tasks/unique-names
                    katello.tasks/timestamps]} 
         (println "starting a selenium session.")
         (try
           (com.redhat.qe.tools.SSLCertificateTruster/trustAllCerts)
           (com.redhat.qe.tools.SSLCertificateTruster/trustAllCertsForApacheXMLRPC)
           (start-selenium)
           (switch-new-admin-user *session-user* *session-password*)
           (catch Exception e (.printStackTrace e)))
         (consume-fn)
         (stop-selenium)
         (htmlify "html" [(str (.getName (Thread/currentThread)) ".trace")]
                     "http://hudson.rhq.lab.eng.bos.redhat.com:8080/shared/syntaxhighlighter/"))))))

(def runner-config 
  {:thread-runner thread-runner
   :setup (fn [] (println "initializing.") (init))
   :watchers {:stdout-log (fn [k r o n]
                            (let [[_ d _] (data/diff o n)]
                              (doseq [[{:keys [name parameters]} {:keys [status report]}] d]
                                (let [parms-str (if parameters (pr-str parameters) "")]
                                  (if (= status :done)
                                    (println (str (:result report) ": " name parms-str) )
                                    (println (str status ": " name parms-str)))))))
              :screencapture (watch/on-fail
                              (fn [t _] 
                                (browser "screenCapture"
                                         "screenshots"
                                         (str 
                                          (:name t)
                                          (if (:parameters t)
                                            (str "-" (replace (pr-str (:parameters t)) "/" "\\"))
                                            "")
                                          ".png")
                                         false)))}})
