(ns katello.tests.systems
  (:refer-clojure :exclude [fn])
  (:use [test.tree.builder :only [fn]]
        [katello.conf :only [config]]
        [com.redhat.qe.verify :only [verify-that]])
  (:require [katello.tasks :as tasks]
            [katello.api-tasks :as api]
            [katello.validation :as val]))



(defn with-freshly-registered-system [f]
  (f (api/with-admin
       (api/create-system (tasks/uniqueify "newsystem")
                          {:env-name (@config :first-env)
                           :facts (api/random-facts)}))))

(def create-env 
  (fn [] (api/with-admin
          (api/ensure-env-exist (@config :first-env) {:prior "Locker"}))))

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
                        {:env-name (@config :first-env)
                         :system-name (:name sys)})
        (verify-that (= (:environment_id sys)
                        (api/with-admin
                          (api/get-id-by-name :environment (@config :first-env)))))))))

(def subscribe
  (fn []
    (let [provider-name (tasks/uniqueify "subscr-prov")
          product-name (tasks/uniqueify "subscribe-me")]
      (api/with-admin
        (api/create-provider provider-name)
        (api/create-product product-name
                            {:provider-name provider-name}))
      (with-freshly-registered-system
        (fn [sys]
          (tasks/subscribe-system {:system-name (:name sys)
                                   :products [product-name]}))))))

(def create-activation-key
  (fn []
    (tasks/create-activation-key  {:name (tasks/uniqueify "auto-key")
                                   :description "my description"
                                   :environment (@config :first-env)})))

(def remove-activation-key
  (fn []
    (let [ak-name (tasks/uniqueify "auto-key-deleteme")]
      (tasks/create-activation-key {:name ak-name
                                    :description "my description"
                                    :environment (@config :first-env)} )
      (tasks/delete-activation-key ak-name))))

(def activation-key-dupe-disallowed
  (fn []
    (val/duplicate-disallowed tasks/create-activation-key
                              [{:name (tasks/uniqueify "auto-key")
                                :description "my description"
                                :environment (@config :first-env)}])))

