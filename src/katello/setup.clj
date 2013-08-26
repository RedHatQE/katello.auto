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

(def sauce-name "bmorriso")

(def sauce-key "f70c3b5c-f09f-4236-b0aa-250a2fce395d")

(def empty-browser-config {"browserName" "firefox"
                           "platform" "LINUX"
                           "version" "22"
                           "nativeEvents" true
                           ;; :profile
                           #_(doto (ff/new-profile)
                             (ff/enable-native-events true))})
(defn new-remote-grid
  "Returns a remote grid server. See new-remote-driver."
  [url port spec]
  (rs/init-remote-server {:host url
                          :port port
                          :existing true}))

(def default-grid (new-remote-grid (str sauce-name ":" sauce-key "@ondemand.saucelabs.com") 80 empty-browser-config))

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

(defn get-last-sauce-build
  "Returns the last sauce build number used."
  []
  (job/get-all-ids sauce-name sauce-key {:limit 10}))

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
  "Sets the implicit-wait time for the driver and navigates to the specified urlu"
  []
  (browser/implicit-wait 1000)
  (browser/to (@config :server-url)))

(defn set-job-id
  "Sets a thread-local binding of the session-id to *job-id*. This is to allow pass/fail reporting after the browser session has ended."
  []
  (set! *job-id* (second (re-find #"\(([^\)]+)\)" (str (:webdriver browser/*driver*))))))

(defn stop-selenium 
  ([] (browser/quit browser/*driver*))
  ([driver] (browser/quit driver)))

(defmacro with-remote-driver-fn
  "Given a `browser-spec` to start a browser and a `finder-fn` to use as a finding function, execute the forms in `body`, then call `quit` on the browser."
  
  [browser-spec finder-fn & body]
  `(binding [browser/*driver* (new-remote-driver default-grid ~browser-spec)
             browser/*finder-fn* ~finder-fn]
     (try
      ~@body
      (finally
        (browser/quit)))))

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
        (binding [*session-user* user
                  *job-id* nil]
          (consume-fn))))))

(defn on-pass
  "create a watcher that will call f when a test passes."
  [f]
  (watch/watch-on-pred (fn [test report]
                   (let [r (:report report)]
                     (and r (-> r :result (= :pass)))))
                 f))

(def runner-config 
  {:thread-wrapper thread-runner
   :watchers {:stdout-log watch/stdout-log-watcher
              :onpass (on-pass
                       (fn [t _]
                         (if (complement (contains? t :configuration))
                           (let [s-id *job-id*]
                             (job/update-id  sauce-name
                                             sauce-key
                                             s-id {:name (:name t)
                                                   :build 4
                                                   :tags [(:version (rest/get-version))]
                                                   :passed true})))))
              :onfail (watch/on-fail
                       (fn [t e]
                         (if (complement (contains? t :configuration))
                           (let [s-id *job-id*]
                             (job/update-id  sauce-name
                                             sauce-key
                                             s-id {:name (:name t)
                                                   :tags [(:version (rest/get-version))]
                                                   :build 4
                                                   :passed false
                                                   :custom-data {"throwable" (pr-str (:throwable (:error (:report e))))
                                                                 "stacktrace" (-> e :report :error :stack-trace java.util.Arrays/toString)}})))))}})



