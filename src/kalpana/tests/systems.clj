(ns kalpana.tests.systems
  (:use [test-clj.testng :only [gen-class-testng]]
        [kalpana.tests.setup :only [beforeclass-ensure-admin]]
        [kalpana.conf :only [config]])
  (:require [kalpana.tasks :as tasks]
            [kalpana.api-tasks :as api])
  (:import [org.testng.annotations Test]))

(beforeclass-ensure-admin)

(defn ^{Test {:description "Adds a system via REST api and then renames it in the UI"
              :groups ["systems"]}}
  rename_system [_]
  (let [old-name (tasks/timestamp "mysys")
        new-name (tasks/timestamp "yoursys")]
    (api/create-system old-name (@config :admin-org) (api/random-facts))
    (tasks/edit-system old-name :new-name new-name)
    (tasks/navigate :named-systems-page {:system-name new-name})))

(gen-class-testng)


