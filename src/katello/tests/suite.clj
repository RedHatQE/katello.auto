(ns katello.tests.suite
  (:refer-clojure :exclude [fn])
  (:require [katello.tests.setup :as setup]
            [katello.tasks :as tasks]
            [katello.api-tasks :as api]
            [katello.validation :as validate]
            [test-clj.core :as test])
  (:use [test-clj.core :only [fn unsatisfied by-name]]
        [katello.conf :only [config]]
        [com.redhat.qe.auto.selenium.selenium :only [connect browser]]
        [com.redhat.qe.verify :only [verify]]
        [katello.trace :only [trace untrace with-all-in-ns]]))

(declare login-tests org-tests)

(defn suite []
  {:name "startup"
   :procedure (fn [] (setup/start-sel))
   :further-testing (login-tests)})

(defn login-tests []
  [{:name "login as admin"
    :procedure (fn [] (tasks/verify-success
                      #(tasks/login (@config :admin-user)
                                    (@config :admin-password))))
    :further-testing (org-tests)}

   {:name "login as invalid user"
    :pre-fn (constantly true) ;;disables test
    :procedure (fn [] (tasks/login "invalid" "asdf1234"))}])

(defn org-tests []
  [{:name "create an org"
    :procedure (fn [] (tasks/verify-success
                      #(tasks/create-organization
                        (tasks/timestamp "auto-org") "org description")))
    :further-testing [{:name "delete an org"
                       :procedure (fn []
                                    (let [org-name (tasks/timestamp "auto-del")]
                                      (tasks/create-organization org-name "org to delete immediately")
                                      (tasks/delete-organization org-name)
                                      (let [remaining-org-names (doall (map :name (api/all-entities :organization)))]
                                        (verify (not (some #{org-name} remaining-org-names))))))}
                      {:name "duplicate org disallowed"
                       :procedure (fn []
                                    (let [org-name (tasks/timestamp "test-dup")]
                                      (validate/duplicate_disallowed
                                       #(tasks/create-organization org-name "org-description"))))}
                      {:name "name required"
                       :procedure (fn []
                                    (validate/name-field-required
                                     #(tasks/create-organization nil "org description")))}]}])

(defn -main [ & args]
  (with-all-in-ns trace 'katello.tasks 'katello.api-tasks)
  (trace #'test/execute)
  (trace #'verify)
  (test/run-suite (suite)))
