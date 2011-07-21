(ns katello.tests.promotions
  (:require [katello.tasks :as tasks]
            [katello.api-tasks :as api]
            [clojure.contrib.set :as sets])
  
  (:use [katello.conf :only [config]]
        [test-clj.core :only [data-driven]]
        [com.redhat.qe.auto.bz :only [blocked-by-bz-bugs]]
        [com.redhat.qe.verify :only [verify-that]]))

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

  (let [all-envs (map :name (api/all-entities :environment @myorg))
        ensure-env-exist (fn [env-name prior]
                           (tasks/ensure-by
                            (some #{env-name} all-envs)
                            (api/create-environment env-name @myorg
                                                    (@config :admin-user)
                                                    (@config :admin-password)
                                                    :prior-env prior)))]
    (ensure-env-exist first-env locker)
    (ensure-env-exist second-env first-env)))

(defn verify-all-content-present [from in]
  (doseq [content-type (keys from)]
    (let [promoted (content-type from)
          current (content-type in)]
      (verify-that (sets/superset? current promoted)))))

(defn verify-promote-content [org envs content]
  (let [content (zipmap (keys content) (for [val (vals content)]  ;;execute uniqueifying at runtime
                                            (if (fn? val) (val) val)))]
   (doseq [product-name (content :products)]
     (api/create-product product-name @provider-name :description "test product")
     (api/create-repo (tasks/uniqueify "mytestrepo") @myorg product-name "http://blah.com"))
   (doseq [[from-env target-env] (partition 2 1 envs)]
     (tasks/promote-content from-env target-env content)
     (verify-all-content-present content (tasks/environment-content target-env)))))

(defn tests [] [{:configuration true
                 :name "set up promotions"
                 :description "Takes content and promotes it thru more environments.
                               Verifies that it shows up in the new env."
                 :steps setup
                 :more (data-driven
                        {:name "promote content"
                         :pre (blocked-by-bz-bugs "711144"
                                                  "712318"
                                                  "714297")}
                        verify-promote-content
                        [[@myorg [locker first-env] {:products
                                                #(set (tasks/uniqueify "MyProduct" 3))}]
                         [@myorg [locker first-env second-env] {:products
                                                               #(set (tasks/uniqueify "ProductMulti" 3))}]])}])


