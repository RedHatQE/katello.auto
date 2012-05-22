(ns user)

(use 'katello.ui-tasks)
(require 'selenium-server)
(require 'katello.conf)
(require 'katello.setup)

 
(defn new-browser []
  (katello.setup/new-selenium (-> katello.conf/config deref :browser-types first) true)
  (katello.setup/start-selenium))

;;-------------

(katello.conf/init {:selenium-address "localhost:4444"})

(com.redhat.qe.tools.SSLCertificateTruster/trustAllCerts)
(com.redhat.qe.tools.SSLCertificateTruster/trustAllCertsForApacheXMLRPC)

(when katello.conf/*clients*
  (require 'katello.client)
  (katello.client/connect (katello.client/new-runner
                           (first katello.conf/*clients*)
                           "root" nil
                           (@katello.conf/config :client-ssh-key)
                           (@katello.conf/config :client-ssh-key-passphrase)))) ;;<-here for api only
(selenium-server/start)
(new-browser)