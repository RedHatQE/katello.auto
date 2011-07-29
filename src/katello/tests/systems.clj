(ns katello.tests.systems
  (:refer-clojure :exclude [fn])
  (:use [test-clj.core :only [fn]]
        [katello.conf :only [config]])
  (:require [katello.tasks :as tasks]
            [katello.api-tasks :as api]))

(def rename
  (fn [] (let [old-name (tasks/uniqueify "mysys")
              new-name (tasks/uniqueify "yoursys")]
          (tasks/ensure-env-exist (@config :admin-org) "Development" "Locker")
          (api/create-system  old-name (@config :admin-org)  (api/random-facts))
          (tasks/edit-system old-name :new-name new-name)
          (tasks/navigate :named-systems-page {:system-name new-name}))))
