(ns kalpana.tests.sync_management
  (:require [kalpana.tasks :as tasks])
  (:import [org.testng.annotations Test])
  (:use [test-clj.testng :only [gen-class-testng data-driven]]
        [error.handler :only [with-handlers handle ignore]]
        [com.redhat.qe.verify :only [verify]]))

(defn ^{Test {:groups ["sync"]
              :enabled false  ;;no way to get products into the system yet in an automated fashion -jmw 
              :description "Sync a product."}} simple_sync [_]
  (let [myproduct (tasks/timestamp "sync-test1")]
    (tasks/create-provider myproduct  "testing sync"
                                   "http://hudson.rhq.lab.eng.bos.redhat.com:8080/hudson/job/subscription-manager_master/lastSuccessfulBuild/artifact/rpms/x86_64"
                                   "Generic Yum Collection")
    (let [results (tasks/sync-products [myproduct] 60000)]
      (verify (every? #(= "Sync complete." %) (vals results))))))

(gen-class-testng)
