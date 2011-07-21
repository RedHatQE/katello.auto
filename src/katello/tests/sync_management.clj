(ns katello.tests.sync_management
  (:require [katello.tasks :as tasks]
            [katello.api-tasks :as api])
  (:refer-clojure :exclude [fn])
  (:use [test-clj.core :only [data-driven fn]]
        [com.redhat.qe.auto.bz :only [blocked-by-bz-bugs]]
        [error.handler :only [with-handlers handle ignore]]
        [com.redhat.qe.verify :only [verify-that]]
        [katello.conf :only [config]]))

(def provider-name (atom nil))
(def product-name (atom nil))

(defn tests []
  [ {:name "simple sync"
     :description "Sync a product with just a few packages in one repo."
     :pre (blocked-by-bz-bugs "705355" "711105" "712318" "715004")
     :steps (fn []
              (let [myprovider (tasks/uniqueify "sync")
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
                  (verify-that (every? #(= "Sync complete." %) (vals results))))))}])
