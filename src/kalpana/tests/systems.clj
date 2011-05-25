(ns kalpana.tests.systems
  (:use [test-clj.testng :only [gen-class-testng]]
        [com.redhat.qe.verify :only [verify]])
  (:require [kalpana.tasks :as tasks]
            [kalpana.api-tasks :as api]
            [kalpana.validation :as validate])
  (:import [org.testng.annotations Test BeforeClass]))

(defn ^{Test {:description "Adds a system via REST api and then renames it in the UI"
              :groups ["systems"]}}
  rename_system [_]
  (let [old-name (tasks/timestamp "mysys")
        new-name (tasks/timestamp "yoursys")]
    (api/create-system old-name (api/random-facts))
    (tasks/edit-system old-name :new-name new-name)
    (tasks/navigate :named-systems-page {:system-name new-name})))

(gen-class-testng)


