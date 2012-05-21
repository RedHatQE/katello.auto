(ns user)
(import [org.openqa.selenium.server RemoteControlConfiguration SeleniumServer])
(use 'katello.ui-tasks)
(require 'katello.conf)
(require 'katello.setup)

(declare selenium-server)

;;------------

(defn new-selenium-server []
  (let [rcconf (doto (RemoteControlConfiguration.)
                 (.setPort 4444)
                 (.setTrustAllSSLCertificates true))]
    (when selenium-server
      (.stop selenium-server))
    (def ^:dynamic selenium-server (SeleniumServer. rcconf))
    (.start selenium-server)))

(defn new-browser []
  (katello.setup/new-selenium (-> katello.conf/config deref :selenium-browsers first) true)
  (katello.setup/start-selenium)) 

;;-------------

(katello.conf/init)
(com.redhat.qe.tools.SSLCertificateTruster/trustAllCerts)
(com.redhat.qe.tools.SSLCertificateTruster/trustAllCertsForApacheXMLRPC)

(when katello.conf/*clients*
  (require 'katello.client)
  (katello.client/connect (katello.client/new-runner
                           (first katello.conf/*clients*)
                           "root" nil
                           (@katello.conf/config :client-ssh-key)
                           (@katello.conf/config :client-ssh-key-passphrase)))) ;;<-here for api only
(new-selenium-server)
(new-browser)