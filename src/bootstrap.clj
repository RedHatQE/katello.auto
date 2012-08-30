(ns user)

(use 'katello.ui-tasks)
(use '[com.redhat.qe.auto.selenium.selenium :only [browser]])
(use '[clojure.pprint :only (pp pprint)])

(require '[katello.locators :as locators])
(require 'fn.trace)
(require 'selenium-server)
(require 'katello.conf)
(require 'katello.setup)
(require 'test.tree.jenkins)
(require 'test.tree)
(require 'test.tree.reporter)

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

(defmacro trace [& body]
  `(fn.trace/dotrace (katello.conf/trace-list)
                     ~@body))

(defmacro wrap-swank-conn-maybe
  "Produce a wrap-swank function that does nothing, if swank is not available."
  []
  (let [runnersym (gensym "runner")]
    `(defn wrap-swank
       "Allows you to place (swank.core/break) statements anywhere, and the
  swank debugger will stop there, no matter which thread hits that
  line."
       [~runnersym]
       ~(if (resolve 'swank.core.connection/*current-connection*)
          `(fn [test#]
             (let [conn# swank.core.connection/*current-connection*]
               (binding [swank.core.connection/*current-connection* conn#]
                 (~runnersym test#))))
          `(fn [test#]
             (~runnersym test#))))))

(wrap-swank-conn-maybe)

(defn debug [tree]
  (with-redefs [test.tree/runner (-> test.tree/execute
                                    test.tree.jenkins/wrap-tracing
                                    test.tree/wrap-blockers
                                    test.tree/wrap-timer
                                    test.tree/wrap-data-driven
                                    wrap-swank)]
    
    (trace (let [results (test.tree/run tree)]
             (doall (->> results second deref vals (map (comp deref :promise))))
             results))))

(defn quick-report [result]
  (let [reports (-> result second)]
    (binding [test.tree.reporter/*reports* reports]
      (let [fails (into {}
                        (filter (fn [[k v]] (test.tree.reporter/failed? k)) @reports))]
        {:failed-tests fails
         :counts {:failed (count fails)
                  :passed (count (test.tree.reporter/passed-tests))
                  :skipped (count (test.tree.reporter/skipped-tests))}}))))
