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
                           [users :as users])
   
            (katello [tasks :as tasks]
                     [conf :as conf]
                     [api-tasks :as api]
                     [validation :as validate])
   
            [test-clj.core :as test]
            [clojure.contrib.trace :as trace]
            [com.redhat.qe.auto.selenium.selenium :as selenium])
  (:use [test-clj.core :only [fn]]
        [katello.trace :only [dotrace-all]]
        [com.redhat.qe.verify :only [verify-that check]]
        [com.redhat.qe.auto.bz :only [blocked-by-bz-bugs]]))

(declare login-tests org-tests environment-tests provider-tests system-tests user-tests sync-tests)


(defn suite []
  (with-meta
    (test/before-all (fn [] (tasks/navigate :top-level))
     {:name "login as admin"
      :steps login/admin
      :more (conj (concat (org-tests)
                          (provider-tests)
                          (sync-tests)
                          (promotions/tests)
                          (system-tests)
                          (user-tests))
                  {:name "login as invalid user"
                   :pre (constantly true) ;;disables test
                   :steps (fn [] (tasks/login "invalid" "asdf1234"))})})
    (merge {:threads 3} setup/runner-config)))

(defn org-tests []
  [{:name "create an org"
    :steps orgs/create
    :more (concat
           [{:name "delete an org"
             :pre (blocked-by-bz-bugs "716972")
             :steps orgs/delete}
            
            {:name "duplicate org disallowed"
             :pre (blocked-by-bz-bugs "726724")
             :steps orgs/dupe-disallowed}

            {:name "org name required"
             :pre (blocked-by-bz-bugs "726724")
             :steps orgs/name-required}

            {:name "edit an org"
             :steps orgs/edit}]
           
           (test/data-driven {:name "org valid name"
                              :pre (blocked-by-bz-bugs "726724")}
                             orgs/valid-name
                             orgs/valid-name-data)
           
           (environment-tests))}])

(defn environment-tests []
  [{:configuration true
    :name "create a test org"
    :steps envs/create-test-org
    :more [{:name "create environment"
            :pre (blocked-by-bz-bugs "693797" "707274")
            :steps envs/create
            :more [{:name "delete environment"
                    :steps envs/delete}
                   
                   {:name "duplicate environment disallowed"
                    :pre (blocked-by-bz-bugs "726724")
                    :steps envs/dupe-disallowed}
                   
                   {:name "rename an environment"
                    :steps envs/rename}

                   {:name "swap environment paths"
                    :description "Create two promotion paths.  Take
                                  the last env in the 2nd path (z),
                                  and move it to the end of the first
                                  path.  Verify that items in the path
                                  still disallow editing to set z as a
                                  prior."
                    :steps envs/swap-paths}]}

           {:name "environment name required"
            :pre (blocked-by-bz-bugs "726724")
            :steps envs/name-required}]}])

(defn provider-tests []
  [{:name "create a custom provider"
     :steps (fn [] (providers/test-provider :custom))
     :more (concat
            [{:name "rename a provider"
              :steps providers/rename}

             {:name "delete a provider"
              :steps providers/delete}

             {:configuration true
              :name "create provider for testing product creation"
              :steps providers/setup-custom
              :more [{:name "create a product"
                      :steps providers/create-product}
                     {:name "create a repository"
                      :steps providers/create-repo}]}]
            
            (test/data-driven {:name "provider validation"}
                              providers/validation
                              providers/validation-data))}])

(defn sync-tests []
  [{:name "simple sync"
     :description "Sync a product with just a few packages in one repo."
     :pre (blocked-by-bz-bugs "705355" "711105" "712318" "715004" "727674" "727627")
     :steps sync/simple}])

(defn system-tests []
  [{:name "setup environment for systems"
    :configuration true
    :steps systems/create-env
    :pre (blocked-by-bz-bugs "717408" "728357")
    :more [{:name "rename a system"
            :description "Adds a system via REST api and then renames it in the UI"
            :steps systems/rename}
           {:name "system shows up on system environment page"
            :pre (constantly true) ;;disabled
            :steps systems/in-env}]}])

(defn user-tests []
  [{:name "create a user"
    :steps users/create
    :more [{:name "edit a user"
            :pre (blocked-by-bz-bugs "720469")
            :steps users/edit}]}])

(defn -main [ & args]
  (test/run-suite (suite)))
