(ns katello.tests.setup
  (:refer-clojure :exclude [replace])
  (:require [clojure.data :as data]
            (katello [tasks :as tasks]
                     [api-tasks :as api]
                     [client :as client]) 
            [test.tree.watcher :as watch]
            [test.tree.jenkins :as jenkins])
  (:use [clojure.string :only [split replace]]
        [katello.conf]
        fn.trace 
        com.redhat.qe.auto.selenium.selenium))

(defn new-selenium
  "Returns a new selenium client. If running in a REPL or other
   single-session environment, set single-thread to true."
  [browser-string & [single-thread]]
  (let [[host port] (split (@config :selenium-address) #":")
        sel-fn (if single-thread connect new-sel)] 
    (sel-fn host (Integer/parseInt port) browser-string (@config :server-url))))

(defn start-selenium []  
  (browser start)
  (browser open (@config :server-url) jquery-ajax-finished)
  (tasks/login (@config :admin-user) (@config :admin-password)))

(defn switch-new-admin-user
  "Creates a new user with a unique name, assigns him admin
   permissions and logs in as that user."
  [user pw]
  (api/with-creds (@config :admin-user) (@config :admin-password)
    (api/create-user user {:password pw
                           :email (str user "@myorg.org")}))
  (tasks/assign-role {:user user
                      :roles ["Administrator"]})
  (tasks/logout)
  (tasks/login user pw))

(defn stop-selenium []
  (browser stop))

(defn thread-runner
  "A test.tree thread runner function that binds some variables for
   each thread. Starts selenium client for each thread before kicking
   off tests, and stops it after all tests are done."
  [consume-fn]
  (fn []
    (let [thread-number (->> (Thread/currentThread) .getName (re-seq #"\d+") first Integer.)]
      (binding [tracer (per-thread-tracer)
                sel (new-selenium (nth (cycle *browsers*)
                                       thread-number))
                *session-user* (tasks/uniqueify
                                (str (@config :admin-user)
                                     thread-number))
                client/*runner* (when *clients*
                                  (try (client/new-runner (nth *clients* thread-number)
                                                      "root" nil
                                                      (@config :client-ssh-key)
                                                      (@config :client-ssh-key-passphrase))
                                       (catch Exception e (do (.printStackTrace e) e))))]
        (try
          (start-selenium)
          (switch-new-admin-user *session-user* *session-password*)
          (catch Exception e (.printStackTrace e)))
        (consume-fn)
        (stop-selenium)))))

(def runner-config 
  {:thread-runner thread-runner
   :setup (fn []
            (init)
            (com.redhat.qe.tools.SSLCertificateTruster/trustAllCerts)
            (com.redhat.qe.tools.SSLCertificateTruster/trustAllCertsForApacheXMLRPC))
   :watchers {:stdout-log watch/stdout-log-watcher
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
