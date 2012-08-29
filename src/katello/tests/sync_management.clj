(ns katello.tests.sync_management
  (:require [katello.api-tasks :as api]
            [katello.validation :as validate])
  (:refer-clojure :exclude [fn])
  (:use katello.tasks
        [katello.providers :only [create-provider add-product add-repo]]
        [katello.users :only [login create-user]]
        [katello.roles :only [assign-role]]
        [katello.organizations :only [create-organization]]
        katello.sync-management
        [katello.ui-tasks :only [navigate errtype]]
        test.tree.script
        [katello.tests.login :only [login-admin]]
        [bugzilla.checker :only [open-bz-bugs]]
        [tools.verify :only [verify-that]]
        [katello.conf :only [config *environments*]]))

;; Variables

(def plan-name (atom nil))
(def product-name (atom nil))
(def provider-name (atom nil))
(def repo-name (atom nil))

;; Functions

(defn create-sync-test-repo []
  (let [myprovider (reset! provider-name (uniqueify "sync"))
        myproduct (reset! product-name (uniqueify "sync-test1"))
        myrepo (reset! repo-name (uniqueify "testrepo"))]
    (api/with-admin
      (api/create-provider myprovider {:description "provider to test syncing"})
      (api/create-product myproduct {:provider-name myprovider
                                     :description "testing sync"})
      (api/create-repo myrepo
                       {:product-name myproduct
                        :url (@config :sync-repo)}))))

(defn is-complete? [sync-result]
  (= "Sync complete." sync-result))

(defn plan-validate [arg expected]
  (expecting-error (errtype expected)
                   (create-sync-plan arg)))

(defn plan-validation-data []
  [[{:start-time (java.util.Date.) :interval "daily"} :katello.notifications/name-cant-be-blank]
   [{:name "blah" :start-time-literal "" :start-date-literal ""} :katello.notifications/start-date-time-cant-be-blank]])

(defn sync-with-user [user]
  (with-unique [user user
                password "asdf"
                org "org"
                provider "prov"
                product "prod"
                repo "repo"]
    (create-user user {:password password :email "blah@blah.com"})
    (assign-role {:user user :roles ["Administrator"]})
    (try
      (login user password)
      (create-organization org)
      (api/with-creds user password
        (api/with-org org
          (api/create-env-chain [library "Desenvolvemento" "ControleQualidade"])))
      (create-provider {:name provider})
      (add-product {:provider-name provider :name product})
      (add-repo {:provider-name provider
                 :product-name product
                 :name repo
                 :url (@config :sync-repo)})
      (sync-repos [repo])
      (finally (login-admin)))))

;; Tests

(defgroup sync-tests
  :group-setup create-sync-test-repo
  
  (deftest "Sync a small repo"
    (->>
     (sync-repos [@repo-name] 120000)
     vals
     (every? is-complete?)
     verify-that))

  (deftest "Sync a repository where username has non-ascii characters"
    :data-driven true
    :blockers (open-bz-bugs "835586")
    sync-with-user
    [["Mané"]
     ["水煮鱼"]])

  (deftest "Create a sync plan"
    :blockers (open-bz-bugs "729364")

    (create-sync-plan {:name (reset! plan-name (uniqueify "plan"))
                       :description "my plan"
                       :interval "hourly"
                       :start-date (java.util.Date.)})

    (deftest "Change interval of an existing sync plan"
      (edit-sync-plan @plan-name {:interval "Daily"}))


    (deftest "Rename an existing sync plan"
      (with-unique [myplan-name "myplan"
                    new-name "renamedplan"]
        (create-sync-plan {:name myplan-name
                           :description "my plan"
                           :interval "hourly"
                           :start-date (java.util.Date.)})
        (edit-sync-plan myplan-name {:new-name new-name })
        (navigate :named-sync-plan-page {:sync-plan-name new-name})))

    
    (deftest "Sync plan validation"
      :data-driven true
      
      plan-validate
      (plan-validation-data))



    (deftest "Cannot create two sync plans with the same name"
      (with-unique [plan-name "dupe"]
        (validate/expecting-error-2nd-try validate/duplicate-disallowed
          (create-sync-plan {:name plan-name
                             :start-date (java.util.Date.)
                             :description "mydescription"
                             :interval "daily"}))))


    (deftest "Assign a sync plan to multiple products"      
      :blockers (open-bz-bugs "751876")
      
      (let [second-product-name (uniqueify "MySecondProduct")
            product-names [@product-name second-product-name]]
        (api/with-admin
          (api/with-env library
            (api/create-product second-product-name {:provider-name @provider-name
                                                     :description "testing sync"})
            (api/create-repo (uniqueify "testrepo")
                             {:product-name second-product-name
                              :url (@config :sync-repo)}))
          (api/with-env (first *environments*)
            (api/promote {:products (doall
                                     (for [product product-names]
                                       {:product_id (api/get-id-by-name :product product)}))})))
        (sync-schedule {:plan-name @plan-name
                        :products product-names})
        (let [expected-plan @plan-name
              actual-plans (vals (current-sync-plan product-names))]
          (verify-that (every? #(= % expected-plan) actual-plans))))

      
      (deftest "Re-assign a different sync plan to a product"
        (let [plan-name (uniqueify "new plan")
              new-plan {:name plan-name
                        :description "my new plan"
                        :interval "daily"
                        :start-date (java.util.Date.)}]
          (create-sync-plan new-plan)
          (sync-schedule {:plan-name plan-name
                          :products [@product-name]})
          (let [product @product-name]
            (verify-that (= ((current-sync-plan [product]) product)
                            plan-name))))))))
