(ns katello.tests.sync_management
  (:require [katello.tasks :as tasks]
            [katello.api-tasks :as api])
  (:import [org.testng.annotations Test])
  (:use [test-clj.testng :only [gen-class-testng data-driven]]
        [error.handler :only [with-handlers handle ignore]]
        [com.redhat.qe.verify :only [verify]]
        [katello.conf :only [config]]
        [katello.tests.setup :only [beforeclass-ensure-admin]]))

(def provider-name (atom nil))
(def product-name (atom nil))

(beforeclass-ensure-admin)

(defn ^{Test {:groups ["sync"
                       "blockedByBug-705355"
                       "blockedByBug-711105"
                       "blockedByBug-712318"
                       "blockedByBug-715004" ]
              :description "Sync a product."}}
  simple_sync [_]
  (let [myprovider (tasks/timestamp "sync")
        myproduct (tasks/timestamp "sync-test1")]
    (api/create-provider (@config :admin-org)
                         (@config :admin-user)
                         (@config :admin-password)
                         :name myprovider
                         :description "provider to test syncing"
                         :type "Custom")
    (api/create-product myproduct myprovider
                        :description "testing sync"
                        :url "http://meaningless.url")
    (api/create-repo (tasks/timestamp "testrepo") (@config :admin-org) myproduct
                     (@config :sync-repo))
    (let [results (tasks/sync-products [myproduct] 60000)]
      (verify (every? #(= "Sync complete." %) (vals results))))))

(gen-class-testng)
