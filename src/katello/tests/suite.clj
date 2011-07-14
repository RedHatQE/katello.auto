(ns katello.tests.suite
  (:refer-clojure :exclude [fn])
  (:require [katello.tests.setup :as setup]
            [katello.tasks :as tasks]
            [katello.api-tasks :as api]
            [katello.validation :as validate]
            [test-clj.core :as test]
            [clojure.contrib.trace :as trace])
  (:use [test-clj.core :only [fn unsatisfied by-name]]
        [katello.trace :only [with-all-in-ns trace]]
        [katello.conf :only [config]]
        [com.redhat.qe.auto.selenium.selenium :only [connect browser]]
        [com.redhat.qe.verify :only [verify-that check]]))

(declare login-tests org-tests)

(defn suite []
  {:name "startup"
   :configuration true
   :steps (fn [] (setup/start-sel))
   :more (login-tests)})

(defn login-tests []
  [{:name "login as admin"
    :steps (fn [] (tasks/verify-success
                  #(tasks/login (@config :admin-user)
                                (@config :admin-password))))
    :more (org-tests)}

   {:name "login as invalid user"
    :pre-fn (constantly true) ;;disables test
    :steps (fn [] (tasks/login "invalid" "asdf1234"))}])

(def test-org-name (atom nil))

(defn environment-tests []
  [{:configuration true
    :name "create a test org"
    :steps (fn [] (api/create-organization
                  (reset! test-org-name (tasks/uniqueify "env-test"))
                  "organization used to test environments."))
    :more [{:name "create environment"
            :steps (fn [] (tasks/verify-success
                          #(tasks/create-environment @test-org-name
                                                     (tasks/uniqueify "simple-env")
                                                     "simple environment description")))
            :more [{:name "delete environment"
                    :steps (fn [] (let [env-name (tasks/uniqueify "delete-env")]
                                   (tasks/create-environment
                                    @test-org-name
                                    env-name
                                    "simple environment description")
                                   (tasks/verify-success
                                    #(tasks/delete-environment @test-org-name env-name))))}
                   {:name "duplicate environment disallowed"
                    :steps (fn [] (let [env-name (tasks/uniqueify "test-dup")]
                                   (validate/duplicate_disallowed
                                    #(tasks/create-environment
                                      @test-org-name env-name "dup env description")
                                    :expected-error :name-must-be-unique-within-org)))}]}]}])

(defn org-tests []
  [{:name "create an org"
    :steps (fn [] (tasks/verify-success
                  #(tasks/create-organization
                    (tasks/uniqueify "auto-org") "org description")))
    :more (concat
           [{:name "delete an org"
             :steps (fn []
                      (let [org-name (tasks/uniqueify "auto-del")]
                        (tasks/create-organization org-name "org to delete immediately")
                        (tasks/delete-organization org-name)
                        (let [remaining-org-names (doall (map :name (api/all-entities :organization)))]
                          (verify-that (not (some #{org-name} remaining-org-names))))))}
            {:name "duplicate org disallowed"
             :steps (fn []
                      (let [org-name (tasks/uniqueify "test-dup")]
                        (validate/duplicate_disallowed
                         #(tasks/create-organization org-name "org-description"))))}
            {:name "name required"
             :steps (fn []
                      (validate/name-field-required
                       #(tasks/create-organization nil "org description")))}]
           (environment-tests))}])

(defn all-fn-in-ns [ & namespaces]
  (for [namespace namespaces [k v] (ns-interns namespace) :when (fn? (deref v))]
    v))

(defmacro mydotrace [nslist fnlist & forms]
  `(trace/dotrace ~(vec (concat (mapcat all-fn-in-ns nslist) fnlist)) ~@forms))

(defn -main [ & args]
  (with-all-in-ns trace 'katello.tasks 'katello.api-tasks)
  (trace #'test/execute)
  (trace #'check)
  (binding [clojure.contrib.trace/tracer
            (fn [name value]
              (println (str (when name (format "%6s:" name))  value)))]
    (test/run-suite (suite)))
  
  (comment (with-all-in-ns trace 'katello.tasks 'katello.api-tasks)
           (trace #'test/execute)
           (trace #'check)
           (mydotrace (katello.tasks katello.api-tasks)
               (test/execute check)
               )))
