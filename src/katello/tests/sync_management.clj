(ns katello.tests.sync_management
  (:require [katello.tasks :as tasks]
            [katello.api-tasks :as api]
            [katello.validation :as validate])
  (:refer-clojure :exclude [fn])
  (:use [test.tree.builder :only [data-driven]]
        [serializable.fn :only [fn]]
        [com.redhat.qe.verify :only [verify-that]]
        [katello.conf :only [config]]))

(def plan-name (atom nil))
(def product-name (atom nil))
(def provider-name (atom nil))

(def setup
  (fn []
    (let [myprovider (reset! provider-name (tasks/uniqueify "sync"))
          myproduct (reset! product-name (tasks/uniqueify "sync-test1"))]
      (api/with-admin
        (api/create-provider myprovider {:description "provider to test syncing"})
        (api/create-product myproduct {:provider-name myprovider
                                       :description "testing sync"})
        (api/create-repo (tasks/uniqueify "testrepo") {:product-name myproduct
                                                       :url (@config :sync-repo)})))))

(def simple 
  (fn []
    (let [results (tasks/sync-products [@product-name] 120000)]
      (verify-that (every? #(= "Sync complete." %)
                           (vals results))))))

(def create-plan
  (fn [] (tasks/create-sync-plan {:name (reset! plan-name (tasks/uniqueify "plan"))
                                 :description "my plan"
                                 :interval "hourly"
                                 :start-date (java.util.Date.)})))

(def edit-plan
  (fn [] (tasks/edit-sync-plan @plan-name {:interval "Daily"})))


(def rename-plan
  (fn [] (let [myplan-name (tasks/uniqueify "myplan")
              new-name (tasks/uniqueify "renamedplan")]
          (tasks/create-sync-plan {:name myplan-name
                                   :description "my plan"
                                   :interval "hourly"
                                   :start-date (java.util.Date.)})
          (tasks/edit-sync-plan myplan-name {:new-name new-name })
          (tasks/navigate :named-sync-plan-page {:sync-plan-name new-name}))))

(def plan-validate
  (fn [arg expected]
    (validate/field-validation tasks/create-sync-plan [arg]
                               (validate/expect-error expected))))

(defn plan-validation-data []
  [[{:start-time (java.util.Date.) :interval "daily"} :name-cant-be-blank]
   [{:name "blah"} :start-date-time-cant-be-blank]])

(def dupe-disallowed
  (fn []
    (validate/duplicate-disallowed tasks/create-sync-plan [{:name (tasks/uniqueify "dupe")
                                                   :start-date (java.util.Date.)
                                                   :description "mydescription"
                                                   :interval "daily"}])))

(def set-schedules
  (fn []
    (let [second-product-name (tasks/uniqueify "MySecondProduct")
          product-names [@product-name second-product-name]]
      (api/with-admin
        (api/with-env (@config :first-env)
          (api/create-product second-product-name {:provider-name @provider-name
                                                   :description "testing sync"})
          (api/create-repo (tasks/uniqueify "testrepo")
                           {:product-name second-product-name
                            :url (@config :sync-repo)})
          (api/promote {:products product-names})))
      (tasks/sync-schedule {:plan-name @plan-name
                            :products product-names})
      (let [expected-plan @plan-name
            actual-plans (vals (tasks/current-sync-plan product-names))]
        (verify-that (every? #(= % expected-plan) actual-plans))))))

(def reset-schedule
  (fn []
    (let [plan-name (tasks/uniqueify "new plan")
          new-plan {:name plan-name
                    :description "my new plan"
                    :interval "daily"
                    :start-date (java.util.Date.)}]
      (tasks/create-sync-plan new-plan)
      (tasks/sync-schedule {:plan-name plan-name
                            :products [@product-name]})
      (let [product @product-name]
        (verify-that (= ((tasks/current-sync-plan [product]) product)
                        plan-name))))))
