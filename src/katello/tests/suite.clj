(ns katello.tests.suite
  (:refer-clojure :exclude [fn])
  (:require (katello.tests [setup :as setup]
                           [organizations :as orgs]
                           [providers :as providers]
                           [promotions :as promotions]
                           [sync_management :as sync]
                           [login :as login]
                           [environments :as envs]
                           [systems :as systems]
                           [users :as users]
                           [permissions :as permissions]
                           [templates :as templates]
                           [e2e :as e2e])
            
            [test.tree.jenkins :as jenkins])
  (:use test.tree.script
        fn.trace 
        [serializable.fn :only [fn]]
        [bugzilla.checker :only [open-bz-bugs]]))

(defgroup all-katello-tests
      :test-setup login/navigate-toplevel

      login/all-login-tests
      orgs/all-org-tests
      envs/all-environment-tests
      providers/all-provider-tests
      systems/all-system-tests
      sync/all-sync-tests
      users/all-user-tests
      permissions/all-permission-tests
      templates/all-template-tests
      e2e/all-end-to-end-tests
      )

(defn suite
  ([] (suite nil))
  ([group]
     (with-meta (-> group (or "all-katello-tests") symbol resolve deref)
       (merge {:threads (let [user-choice (try (-> (System/getProperty "test.tree.threads")
                                                  (Integer.))
                                               (catch Exception e 3))]
                          (Math/min user-choice 5))} ;
              setup/runner-config))))

;;list of namespaces and fns we want to trace 
(def to-trace 
  '[katello.tasks
    katello.api-tasks
    katello.client
    katello.tests.setup/start-selenium
    katello.tests.setup/stop-selenium
    katello.tests.setup/switch-new-admin-user
    com.redhat.qe.verify/check
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
  (jenkins/run-suite (suite (first args)) {:to-trace to-trace
                              :do-not-trace do-not-trace}))


