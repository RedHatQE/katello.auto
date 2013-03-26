(ns katello.tests.promotions
  (:require katello
            (katello [rest :as rest]
                     [api-tasks :as api] 
                     [changesets :as changesets]
                     [providers :as provider]
                     [environments :as environment]
                     [organizations :as org]
                     [tasks :refer :all] 
                     [fake-content :as fake]
                     [sync-management :as sync]
                     [conf :refer [with-org config *environments* *session-user* *session-password*]]) 
            (test.tree [script :refer :all]
                       [builder :refer [data-driven dep-chain]])
            [serializable.fn :refer [fn]]
            [bugzilla.checker :refer [open-bz-bugs]]
            [test.assert :as assert]
            [clojure.set :refer [index]])
  (:refer-clojure :exclude [fn]))

(declare test-org)

;; Variables

(def provider (atom nil))
(def envs (atom nil))

(def content-map
  {:repos             :repository
   :products          :product
   :packages          :package
   :errata            :erratum
   :errata-top-level  :erratum})


;; Functions

(defn create-test-provider-and-envs "Create a test provider and enviroments." []
  (reset! provider (-> {:name "promo"
                        :description "test provider for promotions"
                        :org (@config :admin-org)}
                       katello/newProvider
                       uniqueify
                       rest/create))
  
  
  (reset! envs (environments/chain
                (for [e (list "Dev" "QE" "Prod")]
                  (-> {:name e
                       :org (@config :admin-org)}
                      katello/newEnvironment
                      uniqueify))))
  
  (environment/create-all @envs))

(defn verify-all-content-present [from in]
  (doseq [content-type (keys from)]
    (let [promoted (content-type from)
          current (content-type in)]
      (assert/is (every? current promoted)))))

(defn verify-promote-content [envs content]
  ;;create content
  
  )

(defn verify-promote-content [envs content]
  (org/switch)
  (let [create-repo-fn
        (fn [prod]
          (let [product-id (-> prod
                              (api/create-product
                               {:provider-name @provider
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
    (doseq [template (content :templates)]
      (let [product-name (uniqueify "templ-prod")
            {:keys [product-id repo-name]} (create-repo-fn product-name)] 
        (doseq [to-env (drop 1 envs)]
          (api/with-env to-env
            (api/promote {:products [{:product_id product-id}]} )))
        (api/with-env library
          (api/create-template {:name template
                                :description "template to be promoted"})
          (api/add-to-template template {:repositories [{:product product-name
                                                              :name repo-name}]})))))
  (doseq [[from-env target-env] (chain envs)] 
    (changesets/promote-delete-content from-env target-env false content)
    (verify-all-content-present content (changesets/environment-content target-env))))

(defn get-content
  "Fetches products, repos, packages, errata content via api's" 
  ([org-name env-name entity-type] 
    (with-org org-name (api/with-env env-name (api/all-entities entity-type))))
  ([org-name env-name repo-name entity-type] 
    (with-org org-name (api/with-env env-name (api/with-repo repo-name (api/all-entities entity-type))))))

(defn content-deleted? 
  "Returns true if content deleted else returns false by comparing current data
   fetched by (get-content) all-items and deleted-items" 
  [deleted-items org-name env-name content-type & [repo-name]]
  (let [find-data      (if (= content-type :packages) :filename :name)
        all-items      (if-not repo-name 
                         (map :name (get-content org-name env-name (content-map content-type)))
                         (try
                           (map find-data (get-content org-name env-name repo-name (content-map content-type)))
                           (catch IllegalArgumentException e nil)))
        pkg-items       (for [items all-items]
                          (.replaceFirst (re-matcher #".rpm" items) ""))
        items           (if (= content-type :packages) pkg-items all-items)]
    
    (not (some (set items) deleted-items))))

(defn content-repromoted? 
  "Returns true if content was repromoted else returns false by comparing current data
   fetched by (get-content) all-items and repromoted-items" 
  [repromoted-items org-name env-name content-type & [repo-name]]
  (let [find-data      (if (= content-type :packages) :filename :name)
        all-items      (if-not repo-name 
                         (map :name (get-content org-name env-name (content-map content-type)))
                         (try
                           (map find-data (get-content org-name env-name repo-name (content-map content-type)))
                           (catch IllegalArgumentException e nil)))
        pkg-items       (for [items all-items]
                          (.replaceFirst (re-matcher #".rpm" items) ""))
        items           (if (= content-type :packages) pkg-items all-items)]
    
    (every? true? (for [repromoted-item repromoted-items]
                    (if (some #{repromoted-item} items) true)))))
                    
(def promo-data
  (runtime-data
   [(take 2 @envs) (->> {:name "MyProduct"} katello/newProduct uniques (take 3) set)]
   [(take 3 @envs) (->> {:name "ProductMulti"} katello/newProduct uniques (take 3) set)]
   [(take 3 @envs) (->> {:name "TemplateMulti"} katello/newTemplate uniques (take 3) set)]

   ))

(def custom-products
  (for [prod (-> fake/custom-provider first :products)]
      (assoc prod :repos
             (for [repo (:repos prod)] 
               (assoc repo :product-name (:name prod))))))

(def custom-data (mapcat :repos custom-products))

(def rh-products 
  (for [prod fake/some-product-repos]
    (assoc prod :repos (vec (for [repo-name (:repos prod)]
                              {:name repo-name :product-name (:name prod)})))))

(def rh-data (mapcat :repos rh-products))

(defn repo-name [prd-name provider-type]
  (let [data (if provider-type custom-data rh-data)]
    (first (map :name (get (index data [:product-name]) {:product-name prd-name})))))

(defn del-items [deletion-data content-type]
  "Converts the deletion-content data so as to be consumed by content-deleted?" 
  (let [grouped-items (doall
                        (for [[m n] (group-by :product-name deletion-data)]
                          (map :name n)))]
    (cond 
      (= content-type :packages) 
      (doall
        (for [pkgs grouped-items]
          pkgs))
      (= content-type :errata)
      (doall
        (for [erratas grouped-items]
          (for [errata erratas]
            (re-find #"\p{Alpha}+_\p{Alpha}+" errata))))
      (= content-type :repos)
      (map :name deletion-data)
    
      (= content-type :products)
      deletion-data)))
    
    

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
  
  (conj
    (deftest "Check for, No Add-link visible if content is not promoted to next-env"
      :data-driven true
      
      (fn [content]
        (org/switch test-org)
        (let [promotion-custom-content {:products (map :name custom-products)}
              promotion-rh-content {:products (map :name fake/some-product-repos)}
              envz (take 3 (unique-names "env3"))]
          (environment/create-path test-org envz)
          (assert/is (changesets/add-link-exists? library content))))
       
      [[{:repos custom-data}]
       [{:packages '({:name "bear-4.1-1.noarch", :product-name "safari-1_0"}
                     {:name "camel-0.1-1.noarch", :product-name "safari-1_0"}
                     {:name "cat-1.0-1.noarch", :product-name "safari-1_0"})}]
       [{:errata '({:name "Bear_Erratum", :product-name "safari-1_0"}
                   {:name "Sea_Erratum", :product-name "safari-1_0"})}]
       [{:errata-top-level '({:name "Bear_Erratum"}
                             {:name "Sea_Erratum"})}]])
  
     
    (dep-chain
      (filter (complement :blockers)
        (concat
          (deftest "Deletion Changeset test-cases for custom-providers and RH-providers"
            :data-driven true
                
            (fn [deletion-content & [provider-type]]
              (org/switch test-org)
              (let [promotion-custom-content {:products (map :name custom-products)}
                    promotion-rh-content     {:products (map :name fake/some-product-repos)}
                    envz                     (take 3 (unique-names "env3"))
                    deletion-data            (flatten (vals deletion-content))
                    product-names            (keys (group-by :product-name deletion-data))
                    content-type             (first (keys deletion-content))
                    mapped-data              (zipmap product-names (del-items deletion-data content-type))] 

                (environment/create-path test-org envz)
                (if provider-type
                  (changesets/promote-delete-content library (first envz) false promotion-custom-content)
                  (changesets/promote-delete-content library (first envz) false promotion-rh-content))
                (changesets/promote-delete-content (first envz) nil true deletion-content)
                (cond
                  (some #{content-type} [:packages :errata])
                  (assert/is (every? true? 
                                     (flatten 
                                       (doall 
                                         (for [[prd-name del-item] mapped-data]
                                           (content-deleted? del-item test-org (first envz) content-type (repo-name prd-name provider-type)))))))
                                   
                  (some #{content-type} [:products :repos])
                  (assert/is (content-deleted? (del-items deletion-data content-type) test-org (first envz) content-type)))))
       
            [[{:products (map :name custom-products)} ["custom"]]
             [{:repos custom-data} ["custom"]]
             [{:packages '({:name "bear-4.1-1.noarch", :product-name "safari-1_0"}
                           {:name "camel-0.1-1.noarch", :product-name "safari-1_0"}
                           {:name "cat-1.0-1.noarch", :product-name "safari-1_0"})} ["custom"]]
             (with-meta
               [{:errata '({:name "Bear_Erratum", :product-name "safari-1_0"}
                           {:name "Sea_Erratum", :product-name "safari-1_0"})} ["custom"]]
               {:blockers (open-bz-bugs "909961")})
             
             [{:products (map :name fake/some-product-repos)}]
             [{:repos rh-data}]
             [{:packages '({:name "bear-4.1-1.noarch", :product-name "Nature Enterprise"}
                           {:name "camel-0.1-1.noarch", :product-name "Zoo Enterprise"}
                           {:name "cat-1.0-1.noarch", :product-name "Nature Enterprise"})}]
             (with-meta 
               [{:errata '({:name "Bird_Erratum", :product-name "Nature Enterprise"}
                           {:name "Gorilla_Erratum", :product-name "Zoo Enterprise"})}]
               {:blockers (open-bz-bugs "909961")})
             (with-meta
               [{:errata-top-level '({:name "Bear_Erratum"}
                                     {:name "Sea_Erratum"})} ["custom"]]
               {:blockers (open-bz-bugs "874850")})])

          (deftest "Re-promote the deleted content"
            :data-driven true
          
            (fn [content]
              (org/switch test-org)
              (let [promotion-custom-content   {:products (map :name custom-products)}
                    deletion-content           content
                    re-promote-content         content
                    envz                       (take 3 (unique-names "env3"))
                    content-type               (first (keys content))
                    deletion-data              (flatten (vals deletion-content))
                    product-names              (keys (group-by :product-name deletion-data))
                    content-type               (first (keys deletion-content))
                    mapped-data                (zipmap product-names (del-items deletion-data content-type))]
                
                (environment/create-path test-org envz)
                (changesets/promote-delete-content library (first envz) false promotion-custom-content)
                (changesets/promote-delete-content (first envz) nil true deletion-content)
                (changesets/promote-delete-content library (first envz) false re-promote-content)
                (cond
                  (some #{content-type} [:packages :errata])
                  (assert/is (every? true? (doall 
                                             (for [[prd-name del-item] mapped-data]
                                               (content-repromoted? del-item test-org (first envz) content-type (repo-name prd-name "custom"))))))
                  
                  (some #{content-type} [:products :repos])
                  (assert/is (content-repromoted? (del-items deletion-data content-type) test-org (first envz) content-type)))))
            
            [[{:repos custom-data}]
             [{:packages '({:name "bear-4.1-1.noarch", :product-name "safari-1_0"}
                           {:name "camel-0.1-1.noarch", :product-name "safari-1_0"}
                           {:name "cat-1.0-1.noarch", :product-name "safari-1_0"})}]
             (with-meta
               [{:errata '({:name "Bear_Erratum", :product-name "safari-1_0"}
                           {:name "Sea_Erratum", :product-name "safari-1_0"})}]
               {:blockers (open-bz-bugs "909961")})]))))))  
