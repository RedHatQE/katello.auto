(ns katello.tests.suite
  (:refer-clojure :exclude [fn])
  (:require (katello.tests organizations providers promotions
                           sync_management login environments
                           systems users permissions templates
                           e2e navigation)
            
            [test.tree.jenkins :as jenkins]
            [katello.setup :as setup]
            [katello.conf :as conf] 
            [clojure.tools.cli :as cli]
            serializable.fn)
  (:use test.tree.script))

(defgroup katello-tests
  :test-setup katello.tests.login/navigate-toplevel

  katello.tests.login/login-tests
  katello.tests.navigation/nav-tests
  katello.tests.organizations/org-tests
  katello.tests.environments/environment-tests
  katello.tests.providers/provider-tests
  katello.tests.systems/system-tests
  katello.tests.sync_management/sync-tests
  katello.tests.users/user-tests
  katello.tests.permissions/permission-tests
  katello.tests.promotions/promotion-tests
  katello.tests.e2e/end-to-end-tests)



(defgroup sam-tests
  :description "All the tests that apply to SAM or headpin."
  :test-setup katello.tests.login/navigate-toplevel
  
  katello.tests.login/login-tests
  katello.tests.navigation/nav-tests
  katello.tests.organizations/org-tests
  katello.tests.providers/redhat-content-provider-tests
  katello.tests.environments/environment-tests
  katello.tests.systems/system-tests
  katello.tests.users/user-tests
  )

(defn make-suite
  ([] (make-suite nil))
  ([group]
     (with-meta (-> group (or "katello.tests.suite/katello-tests")
                   symbol resolve deref)
       (merge setup/runner-config))))

;;list of namespaces and fns we want to trace 
(def to-trace 
  '[katello.tasks
    katello.ui-tasks
    katello.api-tasks
    katello.client
    katello.setup/start-selenium
    katello.setup/stop-selenium
    katello.setup/switch-new-admin-user
    tools.verify/check
    com.redhat.qe.auto.selenium.selenium/call-sel
    com.redhat.qe.config/property-map])

;;set of fns to exclude from tracing
(def do-not-trace 
  #{'katello.tasks/notification 
    'katello.tasks/success?
    'katello.tasks/uniqueify
    'katello.tasks/unique-names
    'katello.tasks/timestamps})

(defn -main [ & args]
  (let [[opts [suite] banner]
        (apply cli/cli args conf/options)]
    (if (:help opts)
      (do (println banner))
      (do
        (conf/init opts)
        (com.redhat.qe.tools.SSLCertificateTruster/trustAllCerts)
        (com.redhat.qe.tools.SSLCertificateTruster/trustAllCertsForApacheXMLRPC)
        (jenkins/run-suite
         (vary-meta (make-suite suite) assoc :threads (:num-threads opts)) 
         {:to-trace to-trace :do-not-trace do-not-trace})))))
