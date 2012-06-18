(ns selenium-server
  (:import [org.openqa.selenium.server RemoteControlConfiguration SeleniumServer]))

(declare selenium-server)

(defn stop "stop a running selenium server"
  []
  (.stop selenium-server))
  
  

(defn start "Start a selenium server on port 4444"
  []
  (let [rcconf (doto (RemoteControlConfiguration.)
                 (.setPort 4444)
                 (.setTrustAllSSLCertificates true))]
    (when selenium-server
      (try
        (stop)
        (catch Exception _ nil)))    
    (def ^:dynamic selenium-server (SeleniumServer. rcconf))
    (when (re-matches (re-pattern "^1\\.7.*") (System/getProperty "java.version"))
      (System/setProperty "jsse.enableSNIExtension" "false"))
    (.start selenium-server)))

