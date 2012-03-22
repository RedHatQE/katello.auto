(ns katello.tests.promotions
  (:require [katello.api-tasks :as api])
  (:use katello.tasks
        [katello.conf :only [config *environments*]]
        [test.tree.builder :only [data-driven dep-chain]]
        [serializable.fn :only [fn]]
        [bugzilla.checker :only [open-bz-bugs]]
        [clj-http.client :only [with-connection-pool]]
        [com.redhat.qe.verify :only [verify-that]])
  (:refer-clojure :exclude [fn]))

(def provider-name (atom nil))
(def template-name (atom nil))
(def envs (atom nil))

(defn chain-envs
  "Given a list of environments, and a function of two neighboring
   environments in the chain, invoke f on each successive pair (eg:
   envs ['a' 'b' 'c'] -> (f 'a' 'b'), (f 'b' 'c')"
  [envs f]
  (doseq [[from-env target-env] (partition 2 1 envs)]
      (f from-env target-env)))

(def setup
  (fn []
    (reset! provider-name (uniqueify "promo-"))
               
    (api/with-admin
      (api/create-provider  @provider-name {:description "test provider for promotions"})
      (reset! envs (conj *environments* library))
      (chain-envs @envs (fn [prior curr] 
                         (api/ensure-env-exist curr {:prior prior}))))))

(defn verify-all-content-present [from in]
  (doseq [content-type (keys from)]
    (let [promoted (content-type from)
          current (content-type in)]
      (verify-that (every? current promoted)))))

(defn verify-promote-content [envs content]
  (with-connection-pool {:timeout 10 :threads 1 :insecure? true}
    (let [create-repo-fn
          (fn [prod]
            (let [product-id (-> prod
                                (api/create-product
                                 {:provider-name @provider-name
                                  :description "test product"})
                                :id)
                  repo-name (uniqueify "mytestrepo")]
              (api/create-repo repo-name
                               {:product-name prod
                                :url (@config :sync-repo)})
              (binding [api/*product-id* product-id]
                (api/sync-repo repo-name))
              {:repo-name repo-name
               :product-id product-id}))]
      (doseq [product-name (content :products)]
        (api/with-admin (create-repo-fn product-name)))
      (doseq [template-name (content :templates)]
        (api/with-admin
          (let [product-name (uniqueify "templ-prod")
                {:keys [product-id repo-name]} (create-repo-fn product-name)] 
            (doseq [to-env (drop 1 envs)]
              (api/with-env to-env
                (api/promote {:products [{:product_id product-id}]} )))
            (api/with-env library
              (api/create-template {:name template-name
                                    :description "template to be promoted"})
              (api/add-to-template template-name {:repositories [{:product product-name
                                                                  :name repo-name}]}))))))
    (chain-envs envs (fn [from-env target-env]
                      (promote-content from-env target-env content)
                      (verify-all-content-present content (environment-content target-env))))))

(def promo-data
  [(fn [] [(take 2 @envs)
          {:products (set (take 3 (unique-names "MyProduct")))}])
   (fn [] [(take 3 @envs)
          {:products (set (take 3 (unique-names "ProductMulti")))}])
   (fn [] [(take 3 @envs)
          {:templates (set (take 3 (unique-names "TemplateMulti")))}])
   #_(fn [] [(take 2 @envs)
           {:errata {:advisory "RHEA-2012:0001"
                     :title "Beat_Erratum"
                     :others ["Sea" "Bird" "Gorilla"]}}])])

(def tests
  [{:configuration true
    :name "set up promotions"
    :steps setup
    :blockers (open-bz-bugs "711144" "712318" "714297" "738054" "745315" "784853")
    :more
    (-> {:name "promote content"
        :steps verify-promote-content
        :description "Takes content and promotes it thru more environments.
                            Verifies that it shows up in the new env."}
              
       (data-driven promo-data)
       dep-chain)}])


