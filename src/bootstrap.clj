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


(defn new-browser [& [optmap]]
  (katello.setup/new-selenium (-> katello.conf/config deref :browser-types first) true)
  (katello.setup/start-selenium optmap))


;;-------------

(defmacro trace [& body]
  `(fn.trace/dotrace (katello.conf/trace-list)
     ~@body))

(defmacro debug [tree]
  `(test.tree.debug/debug ~tree (katello.conf/trace-list)))

(defn print-name-result [resulttree]
  (doseq [result @(second resulttree)]
    (println (:name(first result)) 
             (:parameters (:report (second result)))
             (:result (:report (second result))))))

(katello.conf/init {:selenium-address "localhost:4444"})

(com.redhat.qe.tools.SSLCertificateTruster/trustAllCerts)
(com.redhat.qe.tools.SSLCertificateTruster/trustAllCertsForApacheXMLRPC)

(selenium-server/start)
(if-let [locale (@katello.conf/config :locale)]
  (do (selenium-server/create-locale-profile locale)
      (new-browser {:browser-config-opts (katello.setup/config-with-profile
                                           locale)}))
  (new-browser))




