(ns katello.providers
  (:require [katello :as kt]
            [clj-webdriver.taxi :as browser]
            [webdriver :as wd]
            (katello [navigation :as nav]
                     [notifications :as notification] 
                     [ui :as ui]
                     [rest :as rest]
                     [organizations :as organization]
                     [ui-common :as common]
                     [tasks :as tasks])))

;; Locators

(ui/defelements :katello.deployment/any [katello.ui]
       {::new                       "new"
        ::name-text                 {:name "provider[name]"}
        ::provider-description-text {:name "provider[description]"}
        ::repository-url-text       {:name "provider[repository_url]"}
        ::discovery-url-text        {:name "discover_url"}
        ::discover-button           "//input[@value='Discover']"

        ::create-within-product     "new_repos"
        ::create-repositories       "create_repos"
        ::discover-spinner          "//img[@alt='Spinner']"
        ::existing-product-dropdown "window.$(\"#existing_product_select_chzn\").mousedown()"
        ::new-product-name-text     "//input[@name='product_name']"
        ::create-save               "//input[@value='Save']"
        ::cancel-discovery          "//input[@value='Cancel']"
        ::remove-provider-link      (ui/remove-link "providers")
        ::products-and-repositories (ui/third-level-link "products_repos")
        ::repository-discovery      (ui/third-level-link "repo_discovery") 
        ::details-link              (ui/third-level-link "edit_custom_providers")

        ;; products 
        ::add-product              (ui/button-div "Add Product")
        ::create-product           "//input[@value='Create']"
        ::product-name-text        "//*[@name='product[name]']"
        ::product-label-text       "//*[@name='product[label]']"
        ::product-description-text "//*[@name='product[description]']"
        ::update-prd-gpg-keys      "//div[contains(@class,'edit_select_product_gpg')]"
        ::prd-gpg-select    "//select[@name='product[gpg_key]']"
        ::save-updated-gpg-key     "//div[@name='product[gpg_key]']//button[contains(.,'Save')]"
        ::remove-product           (ui/remove-link "products")})

(wd/template-fns
 {repo-create-checkbox    "//table[@id='discovered_repos']//label[normalize-space(.)='%s']//input"
  new-product-radio-btn   "//input[@name='new_product' and @value='%s']"
  existing-product-select "//div[@id='existing_product_select_chzn']//li[normalize-space(.)='%s']"}) 

;; Nav

(nav/defpages :katello.deployment/any katello.menu
  [::custom-page
   [::new-page (nav/browser-fn (click ::new))]
   [::named-page (fn [ent] (nav/choose-left-pane (kt/provider ent)))
    [::products-page (nav/browser-fn (click ::products-and-repositories)
                                     #_(sleep 2000))
     [::named-product-page (fn [ent] (->> ent kt/product :name ui/editable (browser/click)))]]
    [::details-page (nav/browser-fn (click ::details-link))]
    [::repo-discovery-page (nav/browser-fn (click ::repository-discovery))]]]) 

;; Tasks

(defn- create
  "Creates a custom provider with the given name and description."
  [{:keys [name description org]}]
  {:pre [(instance? katello.Organization org)]} 
  (nav/go-to ::new-page org)
  (browser/quick-fill-submit {::name-text (or name "")}
                             {::provider-description-text (or description "")}
                             {::create-save browser/click})
  (notification/success-type :prov-create))

(defn- add-product
  "Adds a product to a provider, with the given name and description."
  [{:keys [provider name description gpg-key]}]
   {:pre [(instance? katello.Provider provider)
          (instance? katello.Organization (kt/org provider))]} 
  (nav/go-to ::products-page provider)
  (browser/click ::add-product)
  (when gpg-key (browser/select ::prd-gpg-select gpg-key))
  (browser/quick-fill-submit {::product-name-text (or name "")}
                             {::product-description-text (or description "")}
                             {::create-product browser/click})
  (notification/success-type :prod-create))

(defn- update-product
  "Updates product. Currently the properties of a product that
   can be edited are description and gpg-key"
  [product {:keys [gpg-key]}]
  (when (not= (:gpg-key product) gpg-key) 
    (nav/go-to product)
    (wd/->browser (click  ::update-prd-gpg-keys)
                   (select ::prd-gpg-select gpg-key)
                   (click  ::save-updated-gpg-key)
                   (click  ::ui/confirmation-yes))
    (notification/success-type :prod-update)))

(defn- delete-product
  "Deletes a product from the given provider."
  [{:keys [provider] :as product}]
   {:pre [(not-empty provider)
          (instance? katello.Product product)]}
  (nav/go-to product)
  (browser/click ::remove-product)
  (browser/click ::ui/confirmation-yes)
  (notification/success-type :prod-destroy))

(defn- delete
  "Deletes the named custom provider."
  [provider]
  {:pre [(instance? katello.Provider provider)]}
  (nav/go-to provider)
  (browser/click ::remove-provider-link)
  (browser/click ::ui/confirmation-yes)
  (notification/success-type :prov-destroy))

(defn- edit
  "Edits the named custom provider. Takes an optional new name, and
  new description."
  [provider updated]
  {:pre [(instance? katello.Provider provider)
         (instance? katello.Provider updated)]}
  (nav/go-to ::details-page provider)
  (common/in-place-edit {::name-text (:name updated)
                         ::provider-description-text (:description updated)}))

(defn create-discovered-repos-within-product
  "Autodiscovers repositories at the provided url and creates the
  selected repositories within the named product. Optional keys:
  cancel - cancels the repo discovery search shortly after starting it.
  new-prod - creates a new product instead of adding repos to an existing one"
  [product discoverable-url enabled-urls & [{:keys [new-prod cancel]}]]
  (nav/go-to ::repo-discovery-page product)
  (browser/quick-fill-submit {::discovery-url-text discoverable-url}
                             {::discover-button browser/click})
  (if cancel
    (do
      (Thread/sleep 3000)
      (browser/click ::cancel-discovery))
    (do
      (Thread/sleep 2000)
      (browser/wait-until  #(not (browser/visible? ::discover-spinner)) 120000 2000)
      (doseq [url enabled-urls] (browser/click (repo-create-checkbox url)))
      (browser/click ::create-within-product)
      (if new-prod
        (do
          (browser/click (new-product-radio-btn "true"))
          (browser/input-text ::new-product-name-text (:name product)))
        (do
          (browser/execute-script ::existing-product-dropdown)
          (wd/move-to (existing-product-select (:name product)))))
      (browser/click ::create-repositories)
      (notification/success-type :repo-create)))) 

(extend katello.Provider
  ui/CRUD {:create create
           :delete delete
           :update* edit}

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

  tasks/Uniqueable  tasks/entity-uniqueable-impl

  nav/Destination {:go-to (partial nav/go-to ::named-page)})

(extend katello.Product
  ui/CRUD {:create add-product
           :delete delete-product
           :update* update-product}

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
               :read (partial rest/read-impl id-url)
               })

  tasks/Uniqueable  tasks/entity-uniqueable-impl

  nav/Destination {:go-to (partial nav/go-to ::named-product-page)})

