(ns katello.setup
  (:refer-clojure :exclude [replace])
  (:require (test.tree
             [watcher :as watch]
             [jenkins :as jenkins])
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
            [clj-webdriver.taxi :as browser]
            [clj-webdriver.firefox :as ff]
            [clj-webdriver.remote.server :as rs]
            [sauce-api.jobs :as job]
            [webdriver :as wd]))

(def ^:dynamic *job-id* nil)

(def sauce-configs
  {:chrome+win8   {"browserName" "chrome"
                   "platform" "WIN8"
                   "version" "27"
                   "nativeEvents" true}
   :firefox+linux {"browserName" "firefox"
                   "platform" "LINUX"
                   "version" "23"
                   "nativeEvents" true}
   :firefox+win8  {"browserName" "firefox"
                   "platform" "WIN8"
                   "version" "27"
                   "nativeEvents" true}
   :firefox+osx   {"browserName" "firefox"
                   "platform" "MAC"
                   "version" "21"
                   "nativeEvents" true}
   :ie+win8       {"browserName" "internet explorer"
                   "platform" "WIN8"
                   "version" "10"
                   "nativeEvents" false}})

(def empty-browser-config {"browserName" "firefox"
                           "platform" "LINUX"
                           "nativeEvents" false
                           ;; :profile
                           #_(doto (ff/new-profile)
                               (ff/enable-native-events true))})

(def empty-local-browser-config {:browser :firefox
                                 :profile (doto (ff/new-profile)
                                            (ff/enable-native-events true))})

(defn new-remote-grid
  "Returns a remote grid server. See new-remote-driver."
  [host & [port]]
  (rs/init-remote-server {:host host, :port (or port 80), :existing true}))

(def sauce-host
  (partial format "%s:%s@ondemand.saucelabs.com"))

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

(defn new-remote-driver
  "Returns a remote selenium webdriver browser on the specified selenium grid server."
  [server & [{:keys [browser-config-opts]}]]
  (rs/new-remote-driver server {:capabilities (or browser-config-opts empty-browser-config)}))

(defn new-selenium
  "Returns a local selenium webdriver browser."
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
  "Opens katello url (to a quick-loading page, not dashboard), and logs in"
  []
  (browser/window-maximize)
  (browser/to (str (@config :server-url) "/users"))
  (login))

(defn stop-selenium
  ([] (browser/quit browser/*driver*))
  ([driver] (browser/quit driver)))

(defn sauce-attributes [test]
  (let [full-ver (:version (rest/get-version))
        [_ ver build] (re-find #"(.*-\d+)\.(.*)" full-ver)
        build (-> (re-find #"\.(\d+)\." build)
                  second
                  (or 1))]
    
    {:name (:name test)
     :tags [ver full-ver]
     :build (Integer/parseInt build)}))

(defn harness-middleware
  "Returns a function that runs test.tree tests with all middleware that katello needs."
  []
  (let [sel-config {:selenium-server (cond (@config :sauce-user)
                                           (new-remote-grid
                                            (sauce-host (@config :sauce-user)
                                                        (@config :sauce-key)))
                                           
                                           (@config :selenium-address)
                                           (apply new-remote-grid (split (@config :selenium-address) #":")) ; other remote wd

                                           :else (new-selenium)) ; local
                    :capabilities-chooser-fn (constantly empty-browser-config)
                    :finder-fn wd/locator-finder-fn}]
    
    (if (@config :sauce-user)
      (jenkins/debug+sauce-middleware (-> config
                                          deref
                                          (select-keys [:sauce-user :sauce-key])
                                          (merge sel-config)
                                          (assoc :sauce-job-attributes-fn sauce-attributes)))
      (jenkins/debug+webdriver-middleware sel-config))))

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

        ;;create the admin user
        (rest/create user)
        (rest/http-post (rest/url-maker [["api/users/%s/roles" [identity]]] user) {:body
                                                                                   {:role_id 1}})
        (binding [*session-user* user]
          (consume-fn))))))

(def runner-config
  {:thread-wrapper thread-runner
   :watchers {:stdout-log watch/stdout-log-watcher}})
