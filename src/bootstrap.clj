(ns user)

(use 'katello.ui-tasks)
(use '[com.redhat.qe.auto.selenium.selenium :only [browser]])
(use '[clojure.pprint :only (pp pprint)])

(require '[katello.locators :as locators])
(require 'fn.trace)
(require 'selenium-server)
(require 'katello.conf)
(require 'katello.setup)
(require 'test.tree.debug)

(defn new-browser []
  (katello.setup/new-selenium (-> katello.conf/config deref :browser-types first) true)
  (katello.setup/start-selenium))


;;-------------

(defmacro trace [& body]
  `(fn.trace/dotrace (katello.conf/trace-list)
     ~@body))

(defmacro debug [tree]
  `(test.tree.debug/debug ~tree (katello.conf/trace-list)))

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

