(ns katello.tests.systems
  (:refer-clojure :exclude [fn])
  (:use [test-clj.core :only [fn]]
        [katello.conf :only [config]])
  (:require [katello.tasks :as tasks]
            [katello.api-tasks :as api]))

(def env-name "Development")

(def create-env 
  (fn [] (api/ensure-env-exist (@config :admin-org) env-name "Locker")))

(def rename
  (fn [] (let [old-name (tasks/uniqueify "mysys")
              new-name (tasks/uniqueify "yoursys")] 
          (api/create-system old-name (@config :admin-org) env-name (api/random-facts))
          (tasks/edit-system old-name :new-name new-name)
          (tasks/navigate :named-systems-page {:system-name new-name}))))

(def in-env
  (fn [] (let [system-name (tasks/uniqueify "newsystem")]
          api/create-system system-name (@config :admin-org) env-name (api/random-facts)
          (tasks/navigate :named-systems-page {:system-name system-name}))))
