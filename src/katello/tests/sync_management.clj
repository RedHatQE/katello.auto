(ns katello.tests.sync_management
  (:require [katello.tasks :as tasks]
            [katello.api-tasks :as api])
  (:refer-clojure :exclude [fn])
  (:use [test-clj.core :only [data-driven fn]]
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
