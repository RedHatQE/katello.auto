(ns katello.tests.sync_management
  (:require [katello.tasks :as tasks]
            [katello.api-tasks :as api])
  (:refer-clojure :exclude [fn])
  (:use [test.tree :only [data-driven fn]]
        [com.redhat.qe.verify :only [verify-that]]
        [katello.conf :only [config]]))

(def simple 
  (fn [] (let [myprovider (tasks/uniqueify "sync")
              myproduct (tasks/uniqueify "sync-test1")]
          (api/create-provider (@config :admin-org)
                               (@config :admin-user)
                               (@config :admin-password)
                               :name myprovider
                               :description "provider to test syncing"
                               :type "Custom")
          (api/create-product myproduct myprovider
                              :description "testing sync"
                              :url "http://meaningless.url")
          (api/create-repo (tasks/uniqueify "testrepo") (@config :admin-org) myproduct
                           (@config :sync-repo))
          (let [results (tasks/sync-products [myproduct] 60000)]
            (verify-that (every? #(= "Sync complete." %) (vals results)))))))

(def plan-name (atom nil))

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

(def sync-plan-validate
  (fn [arg expected]
    (validate/field-validation (partial tasks/create-sync-plan arg)
                               expected)))
(def sync-plan-validation-data
  (map (fn [[args expected]]
         [(partial tasks/create-sync-plan args)
          expected])
       [[{} :name-cant-be-blank]
        [{:name "blah"} :start-date-time-cant-be-blank]]))
