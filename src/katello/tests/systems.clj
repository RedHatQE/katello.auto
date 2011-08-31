(ns katello.tests.systems
  (:refer-clojure :exclude [fn])
  (:use [test.tree :only [fn]]
        [katello.conf :only [config]]
        [com.redhat.qe.verify :only [verify-that]])
  (:require [katello.tasks :as tasks]
            [katello.api-tasks :as api]
            [katello.validation :as val]))

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
      (api/create-product {:name product-name
                           :provider-name provider-name})
      (with-freshly-registered-system
        (fn [sys]
          (tasks/subscribe-system {:system-name (:name sys)
                                   :products [product-name]}))))))

(def create-activation-key
  (fn []
    (tasks/create-activation-key  {:name (tasks/uniqueify "auto-key")
                                   :description "my description"
                                   :environment env-name})))

(def remove-activation-key
  (fn []
    (let [ak-name (tasks/uniqueify "auto-key-deleteme")]
      (tasks/create-activation-key {:name ak-name
                                    :description "my description"
                                    :environment env-name} )
      (tasks/delete-activation-key ak-name))))

(def activation-key-dupe-disallowed
  (fn []
    (val/duplicate-disallowed tasks/create-activation-key
                              [{:name (tasks/uniqueify "auto-key")
                                :description "my description"
                                :environment env-name}])))

