(ns katello.tests.promotions
  (:require [katello.tasks :as tasks]
            [katello.api-tasks :as api]
            [clojure.contrib.set :as sets])
  
  (:use [katello.conf :only [config]]
        [test.tree :only [data-driven fn]]
        [com.redhat.qe.auto.bz :only [blocked-by-bz-bugs]]
        [com.redhat.qe.verify :only [verify-that]])
  (:refer-clojure :exclude [fn]))

(def provider-name (atom nil))
(def myorg (atom nil))

(def locker "Locker")
(def first-env "Development")
(def second-env "Q-eh")

(defn setup []
  (reset! myorg (@config :admin-org))
  (reset! provider-name (tasks/uniqueify "promo-"))
  
  (api/create-provider @myorg (@config :admin-user) (@config :admin-password)
                               :name @provider-name
                               :description "test provider for promotions"
                               :type "Custom")
  (api/ensure-env-exist @myorg first-env locker)
  (api/ensure-env-exist @myorg second-env first-env))

(defn promote-content [from-env to-env content]
  (let [changeset (tasks/uniqueify "changeset")]
    (tasks/create-changeset from-env to-env changeset)
    (tasks/add-to-changeset changeset from-env to-env content)
    (tasks/promote-changeset changeset from-env to-env)))

(defn verify-all-content-present [from in]
  (doseq [content-type (keys from)]
    (let [promoted (content-type from)
          current (content-type in)]
      (verify-that (sets/superset? current promoted)))))

(defn verify-promote-content [org envs content]
  (let [content (zipmap (keys content) (for [val (vals content)]  ;;execute uniqueifying at runtime
                                            (if (fn? val) (val) val)))]
   (doseq [product-name (content :products)]
     (api/create-product {:name product-name
                          :provider-name @provider-name
                          :description "test product"})
     (api/create-repo (tasks/uniqueify "mytestrepo") @myorg product-name "http://blah.com"))
   (doseq [[from-env target-env] (partition 2 1 envs)]
     (promote-content from-env target-env content)
     (verify-all-content-present content (tasks/environment-content target-env)))))

(defn tests [] [{:configuration true
                 :name "set up promotions"
                 :description "Takes content and promotes it thru more environments.
                               Verifies that it shows up in the new env."
                 :steps setup
                 :blockers (blocked-by-bz-bugs "711144"
                                               "712318"
                                               "714297")
                 :more (data-driven
                        {:name "promote content"}
                        verify-promote-content
                        [[@myorg [locker first-env] {:products
                                                     (fn [] (set (tasks/uniqueify "MyProduct" 3)))}]
                         [@myorg [locker first-env second-env] {:products
                                                                (fn [] (set (tasks/uniqueify "ProductMulti" 3)))}]])}])


