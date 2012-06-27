(ns katello.tests.sync_management
  (:require [katello.api-tasks :as api]
            [katello.validation :as validate])
  (:refer-clojure :exclude [fn])
  (:use katello.tasks
        katello.ui-tasks
        test.tree.script
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
  [[{:start-time (java.util.Date.) :interval "daily"} :katello.ui-tasks/name-cant-be-blank]
   [{:name "blah" :start-time-literal "" :start-date-literal ""} :katello.ui-tasks/start-date-time-cant-be-blank]])

;; Tests

(defgroup sync-tests
  :group-setup create-sync-test-repo
  
  (deftest "Sync a small repo"
    :blockers (open-bz-bugs "705355" "711105" "712318" "715004"
                            "727674" "727627" "790246")
    
    (->>
     (sync-repos [@repo-name] 120000)
     vals
     (every? is-complete?)
     verify-that))

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
      (expecting-error validate/duplicate-disallowed
                       (create-sync-plan {:name (uniqueify "dupe")
                                          :start-date (java.util.Date.)
                                          :description "mydescription"
                                          :interval "daily"})))


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
            (api/promote {:products (for [product product-names]
                                      {:product_id (api/get-id-by-name :product product)})})))
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
