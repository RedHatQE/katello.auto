(ns katello.tests.promotions
  (:require (katello [api-tasks :as api] 
                     [changesets :as changesets]
                     [content-search :as content-search]
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
                 (fake/prepare-org-custom-provider  test-org fake/custom-provider)
                 (fake/prepare-org test-org (mapcat :repos fake/some-product-repos)))

 
    (dep-chain
 
        (deftest "Deletion Changeset test-cases for custom-providers and RH-providers"
          :data-driven true
      
          (fn [deletion-content & [data]]
            (org/switch test-org)
            (let [promotion-custom-content {:products (map :name custom-products)}
                  promotion-rh-content {:products (map :name fake/some-product-repos)}
                  [provider-type content-search] data
                  [repo result] content-search
                  envz (take 3 (unique-names "env3"))]
              (environment/create-path test-org envz)
              (if (= "custom" (first data)) 
                (changesets/promote-delete-content library (first envz) false promotion-custom-content)
                (changesets/promote-delete-content library (first envz) false promotion-rh-content))
            (changesets/promote-delete-content (first envz) nil true deletion-content)  
            (if (not= (first (keys deletion-content)) :products)
              (do             
                (content-search/select-content-type :repo-type)
                (content-search/submit-browse-button)
                (content-search/select-environments envz)))
            (cond 
              (= (first (keys deletion-content)) :packages)
              (do
                (content-search/click-repo-desc repo (first envz))
                (assert/is (content-search/get-package-desc) result))
              
              (some #{(first (keys deletion-content))} [:repos :errata])
                (do
                  (assert/is (content-search/get-repo-desc) result)))))
       
          [[{:products (map :name custom-products)} 
              ["custom" [ nil nil]]]
           [{:repos (mapcat :repos custom-products)} 
              ["custom" ["safari-x86_64" [[[true "Packages (32)\nErrata (4)\n"] 
                                           [true "Packages (29)\nErrata (4)\n"] false false] 
                                          [[true ["\nPackages (32)\n" "\nErrata (4)\n"]] 
                                           [true ["\nPackages (29)\n" "\nErrata (4)\n"]] false false]]]]]
           [{:packages '({:name "bear-4.1-1.noarch", :product-name "safari-1_0"} 
                         {:name "camel-0.1-1.noarch", :product-name "safari-1_0"} 
                         {:name "cat-1.0-1.noarch", :product-name "safari-1_0"})} 
              ["custom" ["safari-x86_64" {["mouse" "0.1.12-1.noarch"] "A dummy package of mouse", 
                                          ["cheetah" "1.25.3-5.noarch"] "A dummy package of cheetah", 
                                          ["horse" "0.22-2.noarch"] "A dummy package of horse", 
                                          ["gorilla" "0.62-1.noarch"] "A dummy package of gorilla", 
                                          ["dolphin" "3.10.232-1.noarch"] "A dummy package of dolphin", 
                                          ["cockateel" "3.1-1.noarch"] "A dummy package of cockateel", 
                                          ["shark" "0.1-1.noarch"] "A dummy package of shark", 
                                          ["frog" "0.1-1.noarch"] "A dummy package of frog", 
                                          ["dog" "4.23-1.noarch"] "A dummy package of dog", 
                                          ["tiger" "1.0-4.noarch"] "A dummy package of tiger", 
                                          ["kangaroo" "0.2-1.noarch"] "A dummy package of kangaroo", 
                                          ["giraffe" "0.67-2.noarch"] "A dummy package of giraffe", 
                                          ["lion" "0.4-1.noarch"] "A dummy package of lion", 
                                          ["duck" "0.6-1.noarch"] "A dummy package of duck", 
                                          ["crow" "0.8-1.noarch"] "A dummy package of crow", 
                                          ["trout" "0.12-1.noarch"] "A dummy package of trout", 
                                          ["elephant" "8.3-1.noarch"] "A dummy package of elephant", 
                                          ["squirrel" "0.1-1.noarch"] "A dummy package of squirrel", 
                                          ["penguin" "0.9.1-1.noarch"] "A dummy package of penguin", 
                                          ["pike" "2.2-1.noarch"] "A dummy package of pike", 
                                          ["stork" "0.12-2.noarch"] "A dummy package of stork", 
                                          ["walrus" "0.71-1.noarch"] "A dummy package of walrus", 
                                          ["fox" "1.1-2.noarch"] "A dummy package of fox", 
                                          ["cow" "2.2-3.noarch"] "A dummy package of cow", 
                                          ["chimpanzee" "0.21-1.noarch"] "A dummy package of chimpanzee"}]]]
           [{:errata '({:name "Bear_Erratum", :product-name "safari-1_0"}
                       {:name "Sea_Erratum", :product-name "safari-1_0"})} 
              ["custom" ["safari-x86_64" [[[true "Packages (32)\nErrata (4)\n"] 
                                           [true "Packages (32)\nErrata (2)\n"] false false] 
                                          [[true ["\nPackages (32)\n" "\nErrata (4)\n"]] 
                                           [true ["\nPackages (32)\n" "\nErrata (2)\n"]] false false]]]]]
           [{:repos (mapcat :repos rh-products)}]
             [{:packages '({:name "bear-4.1-1.noarch", :product-name "Nature Enterprise"}
                           {:name "camel-0.1-1.noarch", :product-name "Zoo Enterprise"}
                           {:name "cat-1.0-1.noarch", :product-name "Nature Enterprise"})}]
             [{:errata '({:name "Bird_Erratum", :product-name "Nature Enterprise"}
                         {:name "Gorilla_Erratum", :product-name "Zoo Enterprise"})}]
             [{:products (map :name fake/some-product-repos)}]])))
