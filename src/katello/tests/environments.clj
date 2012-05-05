(ns katello.tests.environments
  (:refer-clojure :exclude [fn])
  (:require [katello.api-tasks :as api])
  (:use test.tree.script
        katello.tasks
        katello.validation
        slingshot.slingshot
        [katello.conf :only [config]]
        [katello.tests.providers :only [with-n-new-orgs]]
        [com.redhat.qe.verify :only [verify-that]]
        [serializable.fn :only [fn]]))

;; Variables

(def test-org-name (atom nil))
(def first-env "dev")

;; Functions

(defn verify-delete-env-restricted-to-this-org
  "Verify that when you create multiple environments with the same
   name in multiple orgs, and you delete one enviroment, the
   environment in the other orgs are not affected. Does the
   verification by navigating to the remaining environment(s). Takes
   the environment name to use, and a list of orgs as arguments. The
   orgs must already exist. See also
   katello.tests.provider/with-n-new-orgs which will handle this
   automatically."
  [env-name orgs]
  (doseq [org orgs]
    (switch-org org)
    (create-environment env-name {:org-name org}))      
  (delete-environment env-name {:org-name (first orgs)})
  (doseq [org (rest orgs)]
    (navigate :named-environment-page {:env-name env-name
                                       :org-name org})))

(defn verify-create-same-env-in-multiple-orgs
  "Verifies that the same environment name can be used independently
   in different organizations. See also
   verify-delete-env-restricted-to-this-org."
  [env-name orgs]
  (doseq [org orgs]                           
    (switch-org org)
    (create-environment env-name {:org-name org})))

(defn setup-environment-with-promoted-content
  "Creates a new environment in the admin org, and promotes a
   product with a sync'd repo into it. Uses the API."
  [env-name]
  (with-unique [provider-name  "prov"
                product-name  "prod"
                repo-name  "repo"]
    (api/with-admin
      (api/create-environment env-name {})
      (api/create-provider provider-name)
      (api/create-product product-name {:provider-name provider-name})
      (api/create-repo repo-name {:product-name product-name :url (@config :sync-repo)})
      (sync-repos [repo-name])
      (api/with-env env-name
        (api/promote {:products [{:product_id (api/get-id-by-name :product product-name)}]})))))

;; Setup

(defn create-test-org
  "Creates a new org for testing environments
   using the API."
  []
  (api/with-admin-creds
    (api/create-organization
     (reset! test-org-name (uniqueify "env-test"))
     {:description "organization used to test environments."})))


;; Tests

(defgroup all-environment-tests
  :group-setup create-test-org
  
  (deftest "Create an environment"
    (create-environment (uniqueify "simple-env") {:org-name @test-org-name
                                                  :description "simple environment description"})

    
    (deftest "Delete an environment" 
      (with-unique [env-name "delete-env"]
        (create-environment env-name {:org-name @test-org-name
                                      :description "simple environment description"})
        (delete-environment env-name {:org-name @test-org-name}))

      
      (deftest "Deleting an environment does not affect another org with the same name environment"
        (with-n-new-orgs 2 verify-delete-env-restricted-to-this-org))


      (deftest "Delete an environment that has had content promoted into it"
        (with-unique [env-name "del-w-content"]
          (setup-environment-with-promoted-content env-name)
          (delete-environment env-name {:org-name (@config :admin-org)})))
      

      (deftest "Verify that only environments at the end of their path can be deleted"

        :description "Creates 3 environments in a single path, tries
                      to delete the middle one, which should fail.
                      Then tries to delete the last one, which should
                      succeed."
        
        (let [envs (take 3 (unique-names "env"))
              org (@config :admin-org)]
          (create-environment-path org envs)
          (expecting-error [:type :env-cant-be-deleted]
                           (delete-environment (second envs) {:org-name org}))    
          (delete-environment (last envs) {:org-name org}))))

    
    (deftest "Cannot create two environments in the same org with the same name"
      (verify-2nd-try-fails-with :name-must-be-unique-within-org
                                 create-environment
                                 (uniqueify "test-dup")
                                 {:org-name @test-org-name :description "dup env description"}))

    
    (deftest "Rename an environment"
      :blockers (constantly ["Renaming is not supported for v1"])
      
      (with-unique [env-name  "rename"
                    new-name  "newname"]
        (create-environment env-name {:org-name @test-org-name
                                      :description "try to rename me!"})
        (edit-environment env-name {:org-name @test-org-name
                                    :new-name new-name})
        (navigate :named-environment-page
                  {:org-name @test-org-name
                   :env-name new-name})))

    
    (deftest "Create environments with the same name but in different orgs" 
      (with-n-new-orgs 2 verify-create-same-env-in-multiple-orgs)))

  
  (deftest "Enviroment name is required"
    (name-field-required create-environment [nil {:org-name @test-org-name
                                                  :description "env description"}])))

















(comment (def environment-tests
   [{:configuration true
     :name "create a test org"
     :steps envs/create-test-org
     :more [{:name "create environment"
             :blockers (open-bz-bugs "693797" "707274")
             :steps envs/create
             :more [{:name "delete environment"
                     :steps envs/delete
                     :more [{:name "delete environment same name different org"
                             :description "Creates the same env name in two different orgs, deletes one and verifies the other still exists."
                             :steps envs/delete-same-name-diff-org}

                            {:name "delete environment with promoted content"
                             :steps envs/delete-env-with-promoted-content
                             :blockers (open-bz-bugs "790246")}

                            {:name "only last environment in path can be deleted"
                             :description "Try to delete an env from the middle of the path, and the last in the path, only the latter should be allowed."
                             :steps envs/no-delete-middle-env
                             :blockers (open-bz-bugs "794799")}]}
                   
                    {:name "duplicate environment disallowed"
                     :blockers (open-bz-bugs "726724")
                     :steps envs/dupe-disallowed}
                   
                    #_(comment "renaming disabled for v1"
                               {:name "rename an environment"
                                :steps envs/rename})

                    {:name "environment namespace limited to org"
                     :steps envs/create-same-name-diff-org}]}

            {:name "environment name required"
             :blockers (open-bz-bugs "726724")
             :steps envs/name-required}]}]))