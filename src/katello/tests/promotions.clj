(ns katello.tests.promotions
  (:require [katello.tasks :as tasks]
            [katello.api-tasks :as api])
  
  (:use [katello.conf :only [config]]
        [test.tree.builder :only [data-driven dep-chain fn]]
        [com.redhat.qe.auto.bz :only [open-bz-bugs]]
        [com.redhat.qe.verify :only [verify-that]])
  (:refer-clojure :exclude [fn]))

(def provider-name (atom nil))
(def template-name (atom nil))
(def locker "Locker")


(def setup
  (fn []
    (reset! provider-name (tasks/uniqueify "promo-"))
               
    (api/with-admin
      (api/create-provider  @provider-name {:description "test provider for promotions"})
      (api/ensure-env-exist (@config :first-env) {:prior locker})
      (api/ensure-env-exist (@config :second-env) {:prior (@config :first-env)}))))

(defn promote-content [from-env to-env content]
  (let [changeset (tasks/uniqueify "changeset")]
    (tasks/create-changeset from-env to-env changeset)
    (tasks/add-to-changeset changeset from-env to-env content)
    (tasks/promote-changeset changeset {:from-env from-env
                                        :to-env to-env
                                        :timeout-ms 300000})))

(defn verify-all-content-present [from in]
  (doseq [content-type (keys from)]
    (let [promoted (content-type from)
          current (content-type in)]
      (verify-that (every? current promoted)))))

(defn verify-promote-content [envs content]
  (let [create-repo-fn
        (fn [prod]
          (api/create-product prod
                              {:provider-name @provider-name
                               :description "test product"})
          (api/create-repo (tasks/uniqueify "mytestrepo")
                           {:product-name prod
                            :url "http://blah.com"}))]
    (doseq [product-name (content :products)]
      (api/with-admin (create-repo-fn product-name)))
    (doseq [template-name (content :templates)]
      (api/with-admin
        (let [product-name (tasks/uniqueify "templ-prod")]
          (create-repo-fn product-name)
          (api/with-env "Locker"
            (api/create-template {:name template-name
                                  :description "template to be promoted"})
            (api/add-to-template template-name {:products [product-name]}))))))
  (doseq [[from-env target-env] (partition 2 1 envs)]
    (promote-content from-env target-env content)
    (verify-all-content-present content (tasks/environment-content target-env))))

(defn tests []
  [{:configuration true
    :name "set up promotions"
    :steps setup
    :blockers (open-bz-bugs "711144"
                            "712318"
                            "714297"
                            "738054"
                            "745315")
    :more (-> {:name "promote content"
              :description "Takes content and promotes it thru more environments.
                            Verifies that it shows up in the new env."}
              
             (data-driven verify-promote-content
                          [ (fn [] [[locker (@config :first-env)] {:products (set (tasks/uniqueify "MyProduct" 3))}])
                            (fn [] [[locker (@config :first-env) (@config :second-env)] {:products (set (tasks/uniqueify "ProductMulti" 3))}])
                            (fn [] [[locker (@config :first-env) (@config :second-env)] {:templates (set (tasks/uniqueify "TemplateMulti" 3))}])]) ;;delay calculation with fn, otherwise, @config will still be empty
             dep-chain)}])


