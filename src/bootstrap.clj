(ns bootstrap)
(do         
   (do (require 'katello.ui-tasks :reload-all)
       (require 'katello.conf :reload)
       (require 'katello.setup :reload)
       (require 'katello.client :reload)
       (katello.conf/init)
       
       (import [org.openqa.selenium.server RemoteControlConfiguration SeleniumServer])
       (com.redhat.qe.tools.SSLCertificateTruster/trustAllCerts)
       (com.redhat.qe.tools.SSLCertificateTruster/trustAllCertsForApacheXMLRPC)

       (let [rcconf (doto (RemoteControlConfiguration.)
                      (.setPort 4444)
                      (.setTrustAllSSLCertificates true))
             selserver (SeleniumServer. rcconf)]
         (.start selserver))        

       
       (when katello.conf/*clients*
         (katello.client/connect (katello.client/new-runner
                                  (first katello.conf/*clients*)
                                  "root" nil
                                  (@katello.conf/config :client-ssh-key)
                                  (@katello.conf/config :client-ssh-key-passphrase))))) ;;<-here for api only
   (katello.setup/new-selenium (-> katello.conf/config deref :selenium-browsers first) true)
   (katello.setup/start-selenium))