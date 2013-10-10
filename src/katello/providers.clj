(ns katello.providers
  (:require [katello :as kt]
            [webdriver :as browser]
            (katello [navigation :as nav]
                     [notifications :as notification]
                     [ui :as ui]
                     [rest :as rest]
                     [organizations :as organization]
                     [ui-common :as common]
                     [tasks :as tasks])))

;; Locators

(ui/defelements :katello.deployment/any [katello.ui]
  {::new-provider             "//a[@class='ng-binding' and contains(.,'New Provider')]"
   ::provider-name-text       "//input[@name='name']"
   ::provider-save            "//button[@ng-click='save(provider)']"
   ::provider-error           "//span[@class='note error']/ul/li"
        
   ::new-product              "//div[@class='nutupane-actions fr']/button[contains (.,'New Product')]"
   ::repository-discovery     "//div[@class='nutupane-actions fr']/button[contains (.,'Repo Discovery')]"
   
   ::discover-url-text        "//input[@placeholder='URL to discover']"
   ::discover-button          "//button[@type='submit' and contains(.,'Discover')]"
   ::cancel-discovery         "//button[@ng-click='cancelDiscovery()']"
   ::discover-spinner         "//button[@ng-click='cancelDiscovery()']//i[contains(@class,'icon-spinner')]"
   ::create-selected          "//button[@ng-click='setupSelected()' and contains(.,'Create Selected')]"
   ::discover-prd-name-text   "//input[@ng-model='createRepoChoices.product.name']"
   ::discover-prd-label-text  "//input[@ng-model='createRepoChoices.product.label']"
   ::discover-prd-prov-select "//select[@ng-model='createRepoChoices.product.provider_id']"
   ::existing-prd-select      "//select[@ng-model='createRepoChoices.existingProductId']"
   ::create-discover-repos    "//button[@ng-click='createRepos()']"
        
   ::product-name-text        "//input[@name='name']"
   ::product-label-text       "//input[@name='label']"
   ::products-provider        "//select[@name='provider_id']"
   ::products-gpg-key         "//select[@name='gpg_key_id']"
   ::product-description-text "//textarea[@name='description']"
   ::product-save             "//span[@class='ng-binding' and contains(.,'Create')]"
   ::product-remove           "//button[contains(@class,'ng-binding') and contains(.,'Remove Product')]"
   ::confirm-product-rm       "//div[@alch-confirm-modal='removeProduct(product)']//button[contains(.,'Yes')]"
        
   ::details-link             "//nav[@class='details-navigation']//a[contains(.,'Details')]"
   ::prd-gpgkey-update        "//div[@selector='product.gpg_key_id']//i[contains(@class,'icon-edit')]"
   ::prd-gpgkey-update-select "//div[@selector='product.gpg_key_id']//select[@ng-model='selector']"
   ::save-updated-gpg-key     "//div[@selector='product.gpg_key_id']//button[contains(.,'Save')]"
   
   ::prd-description-update   "//div[@alch-edit-textarea='product.description']//i[contains(@class,'icon-edit')]"})
        

(browser/template-fns
 {select-product          "//tr[@row-select='product']/td/a[contains(.,'%s')]"
  select-repository       "//a[contains(@href,'repositories') and contains(.,'%s')]"
  repo-create-checkbox    "//table[@alch-table='discoveryTable']//td[normalize-space(.)='%s']/../td/input[@type='checkbox']"
  new-product-radio-btn   "//input[@name='newProduct' and @value='%s']"
  check-provider          "//select[@name='provider_id']/option[normalize-space(.)='%s']"})
  
;; Nav

(nav/defpages :katello.deployment/any katello.menu
  [::products-page
   [::product-page (fn [product] (browser/click (select-product (:name product))))]
    [::product-details-page (fn [_] (browser/click ::details-link))]
   [::new-page (fn [_] (browser/click ::new-product))]
   [::repo-discovery-page (fn [_] (browser/click ::repository-discovery))]]) 

;; Tasks

(defn- create-provider
  "Creates a custom provider with the given name."
  [{:keys [name description org]}]
  {:pre [(instance? katello.Organization org)]} 
  (browser/click ::new-provider)
  (browser/quick-fill [::provider-name-text name
                       ::provider-save browser/click]))

(defn- create-product
  "Creates a custom product, with the given name and description."
  [{:keys [provider name description gpg-key]}]
   {:pre [(instance? katello.Provider provider)
          (instance? katello.Organization (kt/org provider))]} 
  (nav/go-to ::new-page provider)
  (if (browser/exists? (check-provider (provider :name)))
    (browser/select-by-text ::products-provider (provider :name))
    (ui/create provider)) 
  (when gpg-key (browser/select-by-text ::products-gpg-key (:name gpg-key)))
  (browser/quick-fill [::product-name-text name
                       ::product-description-text description
                       ::product-save  browser/click]))

(defn- update-product
  "Updates product. Currently the properties of a product that
   can be edited are description and gpg-key"
  [product {:keys [gpg-key]}]
  (when (not= (:gpg-key product) gpg-key) 
    (nav/go-to ::product-details-page product)
    (browser/click ::prd-gpgkey-update)
    (browser/select-by-text ::prd-gpgkey-update-select gpg-key)
    (browser/click ::save-updated-gpg-key)))

(defn- delete-product
  "Deletes a product from the given provider."
  [{:keys [provider] :as product}]
  {:pre [(not-empty provider)
         (instance? katello.Product product)]}
  (nav/go-to product)
  (browser/click ::product-remove)
  (browser/click ::confirm-product-rm))


(defn create-discovered-repos-within-product
  "Autodiscovers repositories at the provided url and creates the
  selected repositories within the named product. Optional keys:
  cancel - cancels the repo discovery search shortly after starting it.
  new-prod - creates a new product instead of adding repos to an existing one"
  [product discoverable-url enabled-urls & [{:keys [new-prod cancel http?]}]]
  (nav/go-to ::products-page product)
  (browser/click ::repository-discovery)
  (browser/quick-fill [::discover-url-text discoverable-url
                       ::discover-button browser/click])
  (if cancel
    (do
      (Thread/sleep 3000)
      (browser/click ::cancel-discovery))
    (do
      (Thread/sleep 2000)
      (browser/wait-until  #(not (browser/visible? ::discover-spinner)) 150000 2000)
      (doseq [url enabled-urls] (browser/click (repo-create-checkbox url)))
      (browser/click ::create-selected)
      (if new-prod
        (do
          (browser/click (new-product-radio-btn "true"))
          (browser/input-text ::discover-prd-name-text (:name product))
          (browser/select-by-text ::discover-prd-prov-select (:name (kt/provider product))))
        (do
          (browser/select-by-text ::existing-prd-select (:name product))))
      (browser/click ::create-discover-repos))))

(extend katello.Provider
  ui/CRUD {:create create-provider}
 
  rest/CRUD (let [org-url (partial rest/url-maker [["api/organizations/%s/providers" [:org]]])
                  id-url (partial rest/url-maker [["api/providers/%s" [identity]]])]
              {:id rest/id-field
               :query (partial rest/query-by-name org-url)
               :create (fn [{:keys [name description org] :as prov}]
                         {:pre [(instance? katello.Organization org)]}
                         (merge prov
                                (rest/http-post (rest/api-url "api/providers")
                                                {:body {:organization_id (rest/get-id org)
                                                        :provider {:name name
                                                                   :description description
                                                                   :provider_type "Custom"}}})))
               :read (fn [prov]
                       {:pre [(or (nil? (kt/org prov))
                                  (instance? katello.Organization (kt/org prov)))]}
                       (rest/read-impl id-url prov))
               :update (fn [prov new-prov]
                         {:pre [(instance? katello.Provider prov)
                                (instance? katello.Provider new-prov)]}
                         (merge new-prov (rest/http-put (id-url prov)
                                                        {:body {:provider
                                                                (select-keys new-prov [:repository_url])}})))
               :delete (fn [prov] (rest/http-delete (id-url prov)))})

  tasks/Uniqueable  tasks/entity-uniqueable-impl)

(extend katello.Product
  ui/CRUD {:create create-product
           :update* update-product  
           :delete delete-product}

  rest/CRUD (let [id-url (partial rest/url-maker [["api/organizations/%s/products/%s" [:org identity]]])
                  org-prod-url ["api/organizations/%s/products/%s" [:org identity]]
                  query-urls (partial rest/url-maker [["api/organizations/%s/products" [(comp :org :provider)]]
                                                      ["/api/environments/%s/products" [:env]]])]
              {:id rest/id-field
               :query (partial rest/query-by-name query-urls)
               :create (fn [prod]
                         (merge prod
                                (rest/http-post
                                 (rest/url-maker [["api/providers/%s/product_create" [:provider]]] prod)
                                 {:body {:product (select-keys prod [:name :description :gpg_key_name])}})))
               :read (partial rest/read-impl id-url)})

  tasks/Uniqueable  tasks/entity-uniqueable-impl

  nav/Destination {:go-to (partial nav/go-to ::product-page)})

