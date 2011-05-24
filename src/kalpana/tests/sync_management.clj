(ns kalpana.tests.sync_management
  (:require [kalpana.tasks :as tasks])
  (:import [org.testng.annotations Test])
  (:use [test-clj.testng :only [gen-class-testng data-driven]]
        [error.handler :only [with-handlers handle ignore]]
        [com.redhat.qe.verify :only [verify]]
        [kalpana.conf :only [config]]))

(def provider-name (atom nil))
(def product-name (atom nil))

(defn ^{Test {:groups ["sync" "blockedByBug-705355"]
              :description "Sync a product."}}
  simple_sync [_]
  (let [myprovider (tasks/timestamp "sync")
        myproduct (tasks/timestamp "sync-test1")]
    (tasks/create-provider myprovider  "provider to test syncing" :custom)
    (tasks/add-product myprovider myproduct  "testing sync" "http://meaningless.url" true true)
    (tasks/add-repo myprovider myproduct "testrepo" (@config :sync-repo))
    (let [results (tasks/sync-products [myproduct] 60000)]
      (verify (every? #(= "Sync complete." %) (vals results))))))

(gen-class-testng)
