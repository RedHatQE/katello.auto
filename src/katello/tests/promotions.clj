(ns katello.tests.promotions
  (:require [katello.api-tasks :as api])
  (:use katello.tasks
        [katello.conf :only [config]]
        [test.tree.builder :only [data-driven dep-chain]]
        [serializable.fn :only [fn]]
        [com.redhat.qe.auto.bz :only [open-bz-bugs]]
        [com.redhat.qe.verify :only [verify-that]])
  (:refer-clojure :exclude [fn]))

(def provider-name (atom nil))
(def template-name (atom nil))

(def setup
  (fn []
    (reset! provider-name (uniqueify "promo-"))
               
    (api/with-admin
      (api/create-provider  @provider-name {:description "test provider for promotions"})
      (api/ensure-env-exist (@config :first-env) {:prior library})
      (api/ensure-env-exist (@config :second-env) {:prior (@config :first-env)}))))

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
          (api/create-repo (uniqueify "mytestrepo")
                           {:product-name prod
                            :url "http://blah.com"}))]
    (doseq [product-name (content :products)]
      (api/with-admin (create-repo-fn product-name)))
    (doseq [template-name (content :templates)]
      (api/with-admin
        (let [product-name (uniqueify "templ-prod")]
          (create-repo-fn product-name)
          (api/with-env library
            (api/create-template {:name template-name
                                  :description "template to be promoted"})
            (api/add-to-template template-name {:products [product-name]}))))))
  (doseq [[from-env target-env] (partition 2 1 envs)]
    (promote-content from-env target-env content)
    (verify-all-content-present content (environment-content target-env))))

(defn tests []
  [{:configuration true
    :name "set up promotions"
    :steps setup
    :blockers (open-bz-bugs "711144"
                            "712318"
                            "714297"
                            "738054"
                            "745315"
                            "784853")
    :more
    (-> {:name "promote content"
        :steps verify-promote-content
        :description "Takes content and promotes it thru more environments.
                            Verifies that it shows up in the new env."}
              
       (data-driven [(fn [] [[library (@config :first-env)]
                            {:products (set (take 3 (unique-names "MyProduct")))}])
                     (fn [] [[library (@config :first-env)
                             (@config :second-env)]
                            {:products (set (take 3 (unique-names "ProductMulti")))}])
                     (fn [] [[library (@config :first-env)
                             (@config :second-env)]
                            {:templates (set (take 3 (unique-names "TemplateMulti")))}])])
       dep-chain)}])


