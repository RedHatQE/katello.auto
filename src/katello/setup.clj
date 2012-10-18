(ns katello.setup
  (:refer-clojure :exclude [replace])
  (:require [clojure.data :as data]
            [test.tree.watcher :as watch]
            [test.tree.jenkins :as jenkins]
            [selenium-server :refer :all] 
            [clojure.string :refer [split replace]]
            (katello [api-tasks :as api]
                     [client :as client]
                     [conf :refer :all]
                     [tasks :refer :all] 
                     [users :refer [login logout]]
                     [roles :as role])
            [fn.trace :as trace]
            [com.redhat.qe.auto.selenium.selenium :refer :all]))

(defn new-selenium
  "Returns a new selenium client. If running in a REPL or other
   single-session environment, set single-thread to true."
  [browser-string & [single-thread]]
  (let [[host port] (split (@config :selenium-address) #":")
        sel-fn (if single-thread connect new-sel)] 
    (sel-fn host (Integer/parseInt port) browser-string (@config :server-url))))

(defn start-selenium []  
  (->browser
   (start)
   ;;workaround for http://code.google.com/p/selenium/issues/detail?id=3498
   (setTimeout "180000")
   (setAjaxFinishedCondition jquery-ajax-finished)
   (open (@config :server-url) false)
   (setTimeout "60000"))
  
  (login (@config :admin-user) (@config :admin-password)))

(defn switch-new-admin-user
  "Creates a new user with a unique name, assigns him admin
   permissions and logs in as that user."
  [user pw]
  (api/create-user user {:password pw
                         :email (str user "@myorg.org")})
  (role/assign {:user user
                :roles ["Administrator"]})
  (logout)
  (login user pw))

(defn stop-selenium []
   (browser stop))

(defn thread-runner
  "A test.tree thread runner function that binds some variables for
   each thread. Starts selenium client for each thread before kicking
   off tests, and stops it after all tests are done."
  [consume-fn]
  (fn []
    (let [thread-number (->> (Thread/currentThread) .getName (re-seq #"\d+") first Integer.)
          user (uniqueify (str (@config :admin-user) thread-number))]
      (binding [trace/tracer (trace/per-thread-tracer)
                sel (new-selenium (nth (cycle *browsers*)
                                       thread-number))]
        (try 
          (start-selenium)
          (switch-new-admin-user user *session-password*)
          (binding [*session-user* user]
            (consume-fn))
          (finally 
            (stop-selenium)))))))

(def runner-config 
  {:teardown (fn []
                 (when selenium-server/selenium-server 
                 (selenium-server/stop)))
   :thread-runner thread-runner
   :watchers {:stdout-log watch/stdout-log-watcher
              :screencapture (watch/on-fail
                              (fn [t _] 
                                (browser "screenCapture"
                                         "screenshots"
                                         (str 
                                          (:name t)
                                          (if (:parameters t)
                                            (str "-" (System/currentTimeMillis))
                                            "")
                                          ".png")
                                         false)))}})
