(ns katello.tests.systems
  (:use [test-clj.testng :only [gen-class-testng]]
        [katello.tests.setup :only [beforeclass-ensure-admin]]
        [katello.conf :only [config]])
  (:require [katello.tasks :as tasks]
            [katello.api-tasks :as api])
  (:import [org.testng.annotations Test]))

(beforeclass-ensure-admin)

(defn ^{Test {:description "Adds a system via REST api and then renames it in the UI"
              :groups ["systems"
                       "blockedByBug-717408"]}}
  rename_system [_]
  (let [old-name (tasks/uniqueify "mysys")
        new-name (tasks/uniqueify "yoursys")]
    (api/create-system old-name (@config :admin-org) (api/random-facts))
    (tasks/edit-system old-name :new-name new-name)
    (tasks/navigate :named-systems-page {:system-name new-name})))

(gen-class-testng)


