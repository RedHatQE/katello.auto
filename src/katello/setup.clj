(ns katello.setup
  (:refer-clojure :exclude [replace])
  (:require [test.tree.watcher :as watch]
            [selenium-server :refer :all] 
            [clojure.string :refer [split replace]]
            [katello :as kt]
            (katello [login :refer [login logout]]
                     [ui-common :as common]
                     [rest :as rest]
                     [ui :as ui]
                     [client :as client]
                     [conf :refer [config *session-user* *session-org* *browsers*]]
                     [tasks :refer :all] 
                     [users :as user])
            [fn.trace :as trace]
            [com.redhat.qe.auto.selenium.selenium :refer :all])
  (:import [com.thoughtworks.selenium BrowserConfigurationOptions]))

(defn new-selenium
  "Returns a new selenium client. If running in a REPL or other
   single-session environment, set single-thread to true."
  [browser-string & [single-thread]]
  (let [[host port] (split (@config :selenium-address) #":")
        sel-fn (if single-thread connect new-sel)] 
    (sel-fn host (Integer/parseInt port) browser-string (@config :server-url))))

(def empty-browser-config (BrowserConfigurationOptions.))

(defn config-with-profile
  ([locale]
     (config-with-profile empty-browser-config locale))
  ([browser-config locale]
     (.setProfile browser-config locale)))

(def ^{:doc "custom snippet that checks both jQuery and angular"}
  jquery+angular-ajax-finished
  "var errfn = function(f,n) { try { return f(n) } catch(e) {return 0}};
   errfn(function(n){ return selenium.browserbot.getCurrentWindow().jQuery.active }) +
   errfn(function(n) { return selenium.browserbot.getCurrentWindow().angular.element('.ng-scope').injector().get('$http').pendingRequests.length })
    == 0")

(defn start-selenium [& [{:keys [browser-config-opts]}]]  
  (->browser
   (start (or browser-config-opts empty-browser-config))
   ;;workaround for http://code.google.com/p/selenium/issues/detail?id=3498
   (setTimeout "180000")
   (setAjaxFinishedCondition jquery+angular-ajax-finished) 
   (open (@config :server-url) false)
   (setTimeout "60000"))
  (login))

(defn switch-new-admin-user
  "Creates a new user with a unique name, assigns him admin
   permissions and logs in as that user."
  [user]
  (rest/create user)
  (ui/update user assoc :roles [(kt/newRole {:name  "Administrator"})])
  (logout)
  ;;login and set the default org to save time later
  (login user {:default-org *session-org*
               :org *session-org*}))

(defn stop-selenium []
   (browser stop))

(defn thread-runner
  "A test.tree thread runner function that binds some variables for
   each thread. Starts selenium client for each thread before kicking
   off tests, and stops it after all tests are done."
  [consume-fn]
  (fn []
    (let [thread-number (->> (Thread/currentThread) .getName (re-seq #"\d+") first Integer.)
          user (uniqueify (update-in *session-user* [:name] #(format "%s%s" % thread-number)))]
      (binding [sel (new-selenium (nth (cycle *browsers*)
                                       thread-number))]
        (try
          ;;staggered startup
          (Thread/sleep (* thread-number 10000))

          (start-selenium {:browser-config-opts (when-let [locale (@config :locale)]
                                                  (config-with-profile locale))})
          (switch-new-admin-user user)
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
                                          (clojure.string/replace (:name t) #"[/\.,]" "-") 
                                          (if (:parameters t)
                                            (str "-" (System/currentTimeMillis))
                                            "")
                                          ".png")
                                         false)))}})
