(ns katello.tests.systems
  (:refer-clojure :exclude [fn])
  (:use [katello.tasks :exclude [create-activation-key]]
        [serializable.fn :only [fn]]
        [katello.conf :only [config *environments*]]
        [com.redhat.qe.verify :only [verify-that]])
  (:require (katello [api-tasks :as api]
                     [validation :as val]
                     [tasks :as tasks])))

(defn with-freshly-registered-system [f]
  (f (api/with-admin
       (api/with-env (first *environments*)
         (api/create-system (uniqueify "newsystem")
                            {:facts (api/random-facts)})))))

(def create-env 
  (fn [] (api/with-admin
          (api/ensure-env-exist (first *environments*) {:prior library}))))

(def rename
  (fn []
    (with-freshly-registered-system
      (fn [sys]
        (let [new-name (uniqueify "yoursys")] 
          (edit-system (:name sys) :new-name new-name)
          (navigate :named-systems-page {:system-name new-name}))))))

(def in-env
  (fn []
    (with-freshly-registered-system
      (fn [sys]
        (navigate :named-system-environment-page
                  {:env-name (first *environments*)
                   :system-name (:name sys)})
        (verify-that (= (:environment_id sys)
                        (api/with-admin
                          (api/get-id-by-name :environment (first *environments*)))))))))

(def subscribe
  (fn []
    (let [provider-name (uniqueify "subscr-prov")
          product-name (uniqueify "subscribe-me")]
      (api/with-admin
        (api/create-provider provider-name)
        (api/create-product product-name
                            {:provider-name provider-name}))
      (with-freshly-registered-system
        (fn [sys]
          (subscribe-system {:system-name (:name sys)
                             :products [product-name]}))))))

(def create-activation-key
  (fn []
    (tasks/create-activation-key {:name (uniqueify "auto-key")
                                  :description "my description"
                                  :environment (first *environments*)})))

(def remove-activation-key
  (fn []
    (let [ak-name (uniqueify "auto-key-deleteme")]
      (tasks/create-activation-key {:name ak-name
                                    :description "my description"
                                    :environment (first *environments*)} )
      (delete-activation-key ak-name))))

(def activation-key-dupe-disallowed
  (fn []
    (val/duplicate-disallowed katello.tasks/create-activation-key
                              [{:name (uniqueify "auto-key")
                                :description "my description"
                                :environment (first *environments*)}])))

