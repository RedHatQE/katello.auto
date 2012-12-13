(ns selenium-server
  (:require [clojure.java.io :as io])
  (:import [org.openqa.selenium.server RemoteControlConfiguration SeleniumServer]))

(declare selenium-server)

(defn stop "stop a running selenium server"
  []
  (.stop selenium-server))
  
  

(defn start "Start a selenium server on port 4444"
  []
  (let [rcconf (doto (RemoteControlConfiguration.)
                 (.setPort 4444)
                 (.setTrustAllSSLCertificates true)
                 (.setProfilesLocation (java.io.File.
                                        "/tmp")))]
    (when selenium-server
      (try
        (stop)
        (catch Exception _ nil)))    
    (def ^:dynamic selenium-server (SeleniumServer. rcconf))
    (when (re-matches (re-pattern "^1\\.7.*") (System/getProperty "java.version"))
      (System/setProperty "jsse.enableSNIExtension" "false"))
    (.start selenium-server)))


(defn create-locale-profile [locale]
  (let [profile-dir (-> selenium-server
                       .getConfiguration
                       .getProfilesLocation
                       .getCanonicalPath)
        locale-profile-dir (io/file profile-dir locale)]
    (when-not (.exists locale-profile-dir)
      (let [prefs (io/file locale-profile-dir "user.js")]
        (io/make-parents prefs)
        (spit prefs (format
                     "user_pref(\"intl.accept_languages\", \"%s\");\n"
                     locale))))))


