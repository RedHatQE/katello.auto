(ns katello.tests.promotions
  (:require (katello [api-tasks :as api] 
                     [changesets :refer [promote-delete-content]]
                     [providers :as provider]
                     [environments :as environment]
                     [organizations :as org]
                     [tasks :refer :all] 
                     [ui-tasks :refer :all]
                     [fake-content :as fake]
                     [sync-management :as sync]
                     [conf :refer [with-org config *environments*]]) 
            (test.tree [script :refer :all]
                       [builder :refer [data-driven dep-chain]])
            [serializable.fn :refer [fn]]
            [bugzilla.checker :refer [open-bz-bugs]]
            [test.assert :as assert])
  (:refer-clojure :exclude [fn]))

;; Variables

(def provider-name (atom nil))
(def template-name (atom nil))
(def envs (atom nil))

;; Functions

(defn create-test-provider-and-envs "Create a test provider and enviroments." []
  (reset! provider-name (uniqueify "promo-"))
  (api/create-provider  @provider-name {:description "test provider for promotions"})
  (reset! envs (conj *environments* library))
  (api/create-env-chain @envs))

(defn verify-all-content-present [from in]
  (doseq [content-type (keys from)]
    (let [promoted (content-type from)
          current (content-type in)]
      (assert/is (every? current promoted)))))

(defn verify-promote-content [envs content]
  (org/switch)
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
      (create-repo-fn product-name))
    (doseq [template-name (content :templates)]
      (let [product-name (uniqueify "templ-prod")
            {:keys [product-id repo-name]} (create-repo-fn product-name)] 
        (doseq [to-env (drop 1 envs)]
          (api/with-env to-env
            (api/promote {:products [{:product_id product-id}]} )))
        (api/with-env library
          (api/create-template {:name template-name
                                :description "template to be promoted"})
          (api/add-to-template template-name {:repositories [{:product product-name
                                                              :name repo-name}]})))))
  (doseq [[from-env target-env] (chain-envs envs)] 
    (promote-delete-content from-env target-env false content)
    (verify-all-content-present content (environment/content target-env))))

(def promo-data
  (runtime-data
   [(take 2 @envs) {:products (set (take 3 (unique-names "MyProduct")))}]
   [(take 3 @envs) {:products (set (take 3 (unique-names "ProductMulti")))}]
   [(take 3 @envs) {:templates (set (take 3 (unique-names "TemplateMulti")))}]

   #_(comment "promoting errata requires some extra setup, disabled for now"
               [(take 2 @envs) {:errata {:advisory "RHEA-2012:0001"
                                         :title "Beat_Erratum"
                                         :others ["Sea" "Bird" "Gorilla"]}}])))

;; Tests

(defgroup promotion-tests
  :group-setup create-test-provider-and-envs
  :blockers (open-bz-bugs "714297" "738054" "745315" "784853" "845096")
          
  (dep-chain
   (deftest "Promote content"
     :data-driven true
     :description "Takes content and promotes it thru more
                   environments. Verifies that it shows up in the new
                   env."
     verify-promote-content
     promo-data)))


(defgroup deletion-tests
  
  (deftest "Delete custom product contents"
    
    (let [envz (take 3 (unique-names "env3"))
          test-org (uniqueify "custom-org")]
      (org/create test-org)
      (org/switch test-org)
      (environment/create-path test-org envz)
      (fake/prepare-org-custom-provider  test-org fake/custom-provider)
      (let [products (-> fake/custom-provider first :products)
            all-prods (map :name products)]
            (promote-delete-content library (first envz) false {:products all-prods})
            (promote-delete-content (first envz) nil true {:products all-prods}))))
  
  (deftest "Delete fake-RH product contents"
    :blockers (open-bz-bugs "877419")
    
    (let [envz (take 3 (unique-names "env3"))
          test-org (uniqueify "redhat-org")]
      (org/create test-org)
      (org/switch test-org)
      (environment/create-path test-org envz)
      (fake/prepare-org  test-org (mapcat :repos fake/some-product-repos))
      (let [products fake/some-product-repos
            all-prods (map :name products)]
            (promote-delete-content library (first envz) false {:products all-prods})
            (promote-delete-content (first envz) nil true {:products all-prods}))))
  
  (deftest "Delete custom repos"
    
    (let [envz (take 3 (unique-names "env3"))
          test-org (uniqueify "redhat-org")]
      (org/create test-org)
      (org/switch test-org)
      (environment/create-path test-org envz)
      (fake/prepare-org-custom-provider  test-org fake/custom-provider)
      (let [products (-> fake/custom-provider first :products)
            all-prods (map :name products)
            all-repos (apply concat (map :repos products))]
            (promote-delete-content library (first envz) false {:products all-prods
                                                                :repos all-repos})
            (promote-delete-content (first envz) nil true {:products all-prods
                                                           :repos all-repos})))))