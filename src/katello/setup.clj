(ns katello.setup
  (:refer-clojure :exclude [replace])
  (:require [test.tree.watcher :as watch]
            [clojure.string :refer [split replace]]
            [katello :as kt]
            (katello [login :refer [login logout]]
                     [ui-common :as common]
                     [rest :as rest]
                     [ui :as ui]
                     [client :as client]
                     [conf :refer [config *session-user* *session-org* *browsers* *wd-driver*]]
                     [tasks :refer :all] 
                     [users :as user])
            [fn.trace :as trace]
            [clj-webdriver.taxi :as browser]
            [clj-webdriver.firefox :as ff]
            [webdriver :as wd]))

#_(defn new-selenium
  "Returns a new selenium client. If running in a REPL or other
   single-session environment, set single-thread to true."
  [browser-string & [single-thread]]
  (let [[host port] (split (@config :selenium-address) #":")
        sel-fn (if single-thread connect new-sel)] 
    (sel-fn host (Integer/parseInt port) browser-string (@config :server-url))))

(def empty-browser-config {:browser :firefox
                           :profile (doto (ff/new-profile)
                                      (ff/enable-native-events true))})

(defn config-with-profile
  ([locale]
     (config-with-profile empty-browser-config locale))
  ([browser-config locale]
     (empty-browser-config)))

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

(defn new-selenium
  [& [{:keys [browser-config-opts]}]]
  (browser/new-driver (or browser-config-opts empty-browser-config)))

(defn start-selenium [& [{:keys [browser-config-opts]}]]  
  (browser/set-driver! (or browser-config-opts empty-browser-config))
  (browser/set-finder! wd/locator-finder-fn)
  (browser/implicit-wait 2000)
  (browser/to (@config :server-url))
  (login)
  browser/*driver*)

(defn conf-selenium
  []
  #_(browser/set-finder! wd/locator-finder-fn)
  (browser/implicit-wait 2000)
  (browser/to (@config :server-url)))

(defn stop-selenium 
  ([] (browser/quit browser/*driver*))
  ([driver] (browser/quit driver)))

(defn thread-runner
  "A test.tree thread runner function that binds some variables for
   each thread. Starts selenium client for each thread before kicking
   off tests, and stops it after all tests are done."
  [consume-fn]
  (fn []
    (let [thread-number (->> (Thread/currentThread) .getName (re-seq #"\d+") first Integer.)
          user (uniqueify (update-in *session-user* [:name] #(format "%s%s" % thread-number)))]
      (try
        ;;staggered startup
        (Thread/sleep (* thread-number 5000))
        #_(start-selenium {:browser-config-opts (when-let [locale (@config :locale)]
                                                  (config-with-profile locale))})
        (rest/create user)
        (rest/http-post (rest/url-maker [["api/users/%s/roles" [identity]]] user) {:body
                                                                                   {:role_id 1}})
        (binding [*session-user* user]
          (consume-fn))
        (finally 
          #_(stop-selenium))))))



(def runner-config 
  {:teardown (fn []
                 )
   :thread-runner thread-runner
   :watchers {:stdout-log watch/stdout-log-watcher
              ;; :screencapture
              #_(watch/on-fail
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
