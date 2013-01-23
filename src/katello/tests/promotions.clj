(ns katello.tests.promotions
  (:require (katello [api-tasks :as api] 
                     [changesets :as changesets]
                     [providers :as provider]
                     [environments :as environment]
                     [organizations :as org]
                     [tasks :refer :all] 
                     [fake-content :as fake]
                     [sync-management :as sync]
                     [conf :refer [with-org config *environments*]]) 
            (test.tree [script :refer :all]
                       [builder :refer [data-driven dep-chain]])
            [serializable.fn :refer [fn]]
            [bugzilla.checker :refer [open-bz-bugs]]
            [test.assert :as assert])
  (:refer-clojure :exclude [fn]))

(declare test-org)

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
    (changesets/promote-delete-content from-env target-env false content)
    (verify-all-content-present content (changesets/environment-content target-env))))

(def promo-data
  (runtime-data
   [(take 2 @envs) {:products (set (take 3 (unique-names "MyProduct")))}]
   [(take 3 @envs) {:products (set (take 3 (unique-names "ProductMulti")))}]
   [(take 3 @envs) {:templates (set (take 3 (unique-names "TemplateMulti")))}]

   #_(comment "promoting errata requires some extra setup, disabled for now"
               [(take 2 @envs) {:errata {:advisory "RHEA-2012:0001"
                                         :title "Beat_Erratum"
                                         :others ["Sea" "Bird" "Gorilla"]}}])))

(def custom-products
  (for [prod (-> fake/custom-provider first :products)]
      (assoc prod :repos
             (for [repo (:repos prod)] 
               (assoc repo :product-name (:name prod))))))

(def rh-products 
  (for [prod fake/some-product-repos]
    (assoc prod :repos (vec (for [repo-name (:repos prod)]
                              {:name repo-name :product-name (:name prod)})))))

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
  :group-setup (fn []
                 (def ^:dynamic test-org (uniqueify "custom-org"))                 
                 (org/create test-org)           
                 (fake/prepare-org-custom-provider  test-org fake/custom-provider))
                 ;;(fake/prepare-org test-org (mapcat :repos fake/some-product-repos)))
  (dep-chain
    (filter (complement :blockers)
      (concat
        (deftest "Check for, No Add-link visible if content is not promoted to next-env"
          :data-driven true
                
          (fn [content]
            (org/switch test-org)
            (let [promotion-custom-content {:products (map :name custom-products)}
                  promotion-rh-content {:products (map :name fake/some-product-repos)}
                  envz (take 3 (unique-names "env3"))]
              (environment/create-path test-org envz)
              (assert/is (changesets/add-link-exists? library content))))
       
          [[{:repos (mapcat :repos custom-products)}]
           [{:packages '({:name "bear-4.1-1.noarch", :product-name "safari-1_0"})}]])
        
        (deftest "Deletion Changeset test-cases for custom-providers and RH-providers"
          :data-driven true
                
          (fn [deletion-content & [provider-type]]
            (org/switch test-org)
            (let [promotion-custom-content {:products (map :name custom-products)}
                  promotion-rh-content {:products (map :name fake/some-product-repos)}
                  envz (take 3 (unique-names "env3"))]
              (environment/create-path test-org envz)
              (if provider-type 
                (changesets/promote-delete-content library (first envz) false promotion-custom-content)
                (changesets/promote-delete-content library (first envz) false promotion-rh-content))
              (changesets/promote-delete-content (first envz) nil true deletion-content)))
       
          [[{:products (map :name custom-products)} ["custom"]]
           [{:repos (mapcat :repos custom-products)} ["custom"]]
           [{:packages '({:name "bear-4.1-1.noarch", :product-name "safari-1_0"} 
                         {:name "camel-0.1-1.noarch", :product-name "safari-1_0"} 
                         {:name "cat-1.0-1.noarch", :product-name "safari-1_0"})} ["custom"]]
           [{:errata '({:name "Bear_Erratum", :product-name "safari-1_0"} 
                       {:name "Sea_Erratum", :product-name "safari-1_0"})} ["custom"]]
           [{:products (map :name fake/some-product-repos)}]
           [{:repos (mapcat :repos rh-products)}]
           [{:packages '({:name "bear-4.1-1.noarch", :product-name "Nature Enterprise"} 
                         {:name "camel-0.1-1.noarch", :product-name "Zoo Enterprise"} 
                         {:name "cat-1.0-1.noarch", :product-name "Nature Enterprise"})}]
           [{:errata '({:name "Bird_Erratum", :product-name "Nature Enterprise"} 
                       {:name "Gorilla_Erratum", :product-name "Zoo Enterprise"})}]
           (with-meta 
             [{:errata-top-level '({:name "Bear_Erratum"} 
                                   {:name "Sea_Erratum"})} ["custom"]]
             {:blockers (open-bz-bugs "874850")})])
    
        (deftest "Re-promote the deleted content"
          :data-driven true
          
          (fn [content]
            (org/switch test-org)
            (let [promotion-custom-content {:products (map :name custom-products)}
                  deletion-content content
                  re-promote-content content
                  envz (take 3 (unique-names "env3"))]
              (environment/create-path test-org envz)
              (changesets/promote-delete-content library (first envz) false promotion-custom-content)
              (changesets/promote-delete-content (first envz) nil true deletion-content)
              (changesets/promote-delete-content library (first envz) false re-promote-content)))
          [[{:repos (mapcat :repos custom-products)}]
           [{:packages '({:name "bear-4.1-1.noarch", :product-name "safari-1_0"} 
                         {:name "camel-0.1-1.noarch", :product-name "safari-1_0"} 
                         {:name "cat-1.0-1.noarch", :product-name "safari-1_0"})}]
           [{:errata '({:name "Bear_Erratum", :product-name "safari-1_0"} 
                       {:name "Sea_Erratum", :product-name "safari-1_0"})}]])))))

