(ns katello.tests.systems
  (:refer-clojure :exclude [fn])
  (:use [test.tree :only [fn]]
        [katello.conf :only [config]]
        [com.redhat.qe.verify :only [verify-that]])
  (:require [katello.tasks :as tasks]
            [katello.api-tasks :as api]))

(def env-name "Development")

(defn with-freshly-registered-system [f]
  (let [system-name (tasks/uniqueify "newsystem")]  
    (f (api/create-system system-name (@config :admin-org) env-name (api/random-facts)))))

(def create-env 
  (fn [] (api/ensure-env-exist (@config :admin-org) env-name "Locker")))

(def rename
  (fn []
    (with-freshly-registered-system
      (fn [sys]
        (let [new-name (tasks/uniqueify "yoursys")] 
          (tasks/edit-system (:name sys) :new-name new-name)
          (tasks/navigate :named-systems-page {:system-name new-name}))))))

(def in-env
  (fn []
    (with-freshly-registered-system
      (fn [sys]
        (tasks/navigate :named-system-environment-page
                        {:env-name env-name
                         :system-name (:name sys)})
        (verify-that (= (:environment_id sys)
                        (api/get-id-by-name :environment env-name (@config :admin-org))))))))

(def subscribe
  (fn []
    (let [provider-name (tasks/uniqueify "subscr-prov")
          product-name (tasks/uniqueify "subscribe-me")]
      (api/create-provider (@config :admin-org) (@config :admin-user) (@config :admin-password)
                           :name provider-name
                           :type "Custom")
      (api/create-product product-name  provider-name)
      (with-freshly-registered-system
        (fn [sys]
          (tasks/subscribe-system {:system-name (:name sys)
                                   :products [product-name]}))))))
