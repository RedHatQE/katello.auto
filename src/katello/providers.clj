(ns katello.providers
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            katello
            [katello :as kt]
            (katello [navigation :as nav]
                     [notifications :as notification] 
                     [ui :as ui]
                     [rest :as rest]
                     [organizations :as organization]
                     [ui-common :as common]
                     [tasks :as tasks])))

;; Locators

(ui/deflocators
       {::new                       "new"
        ::name-text                 "provider[name]"
        ::description-text          "provider[description]"
        ::repository-url-text       "provider[repository_url]"
        ::discovery-url-text        "discover_url"
        ::discover-button           "//input[@value='Discover']"

        ::create-within-product     "new_repos"
        ::create-repositories       "create_repos"
        ::discover-spinner          "//img[@alt='Spinner']"
        ::existing-product-dropdown "window.$(\"#existing_product_select_chzn\").mousedown()" 
        ::create-save               "//input[@value='Save']"
        ::remove-provider-link      (ui/remove-link "providers")
        ::products-and-repositories (ui/menu-link "products_repos")
        ::repository-discovery      (ui/menu-link "repo_discovery") 
        ::details-link              (ui/menu-link "edit_custom_providers")}

       ;;products
       {::add-product              (ui/button-div "Add Product")
        ::create-product           "//input[@value='Create']"
        ::product-name-text        "//*[@name='product[name]']"
        ::product-label-text       "//*[@name='product[label]']"
        ::product-description-text "//*[@name='product[description]']"
        ::remove-product           (ui/remove-link "products")}
       ui/locators)

(sel/template-fns
 {repo-create-checkbox    "//table[@id='discovered_repos']//label[normalize-space(.)='%s']//input"
  existing-product-select "//div[@id='existing_product_select_chzn']//li[normalize-space(.)='%s']"}) 

;; Nav

(nav/defpages (common/pages)
  [::custom-page
   [::new-page [] (browser click ::new)]
   [::named-page [provider] (nav/choose-left-pane provider)
    [::products-page [] (sel/->browser (click ::products-and-repositories)
                                       (sleep 2000))
     [::named-product-page [product] (browser click (ui/editable (:name product)))]]
    [::details-page [] (browser click ::details-link)]
    [::repo-discovery-page [] (browser click ::repository-discovery)]]]) 

;; Tasks

(defn create
  "Creates a custom provider with the given name and description."
  [{:keys [name description org]}]
   {:pre [(instance? katello.Organization org)]} 
  (nav/go-to ::new-page {:org org})
  (sel/fill-ajax-form {::name-text name
                       ::description-text description}
                      ::create-save)
  (notification/check-for-success {:match-pred (notification/request-type? :prov-create)}))

(defn add-product
  "Adds a product to a provider, with the given name and description."
  [{:keys [provider name description]}]
   {:pre [(instance? katello.Provider provider)
          (instance? katello.Organization (kt/org provider))]} 
  (nav/go-to ::products-page {:provider provider
                              :org (:org provider)})
  (browser click ::add-product)
  (sel/fill-ajax-form {::product-name-text name
                       ::product-description-text description}
                      ::create-product)
  (notification/check-for-success {:match-pred (notification/request-type? :prod-create)}))

(defn delete-product
  "Deletes a product from the given provider."
  [{:keys [provider] :as product}]
   {:pre [(not-empty provider)
          (instance? katello.Product product)]}
  (nav/go-to product)
  (browser click ::remove-product)
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success {:match-pred (notification/request-type? :prod-destroy)}))

(defn delete
  "Deletes the named custom provider."
  [provider]
  {:pre [(instance? katello.Provider provider)]}
  (nav/go-to provider)
  (browser click ::remove-provider-link)
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success {:match-pred (notification/request-type? :prov-destroy)}))

(defn edit
  "Edits the named custom provider. Takes an optional new name, and
  new description."
  [provider updated]
  {:pre [(instance? katello.Provider provider)
         (instance? katello.Provider updated)]}
  (nav/go-to ::details-page {:provider provider
                             :org (:org provider)})
  (common/in-place-edit {::name-text (:name updated)
                         ::description-text (:description updated)}))

(defn create-discovered-repos-within-product
  "Autodiscovers repositories at the provided url and creates the
  selected repositories within the named product."
  [provider product discoverable-url enabled-urls]
  (nav/go-to ::repo-discovery-page {:provider provider
                                    :org (:org provider)})
  (sel/fill-ajax-form {::discovery-url-text discoverable-url} ::discover-button)
  (browser waitForInvisible ::discover-spinner "120000")
  (doseq [url enabled-urls] (browser click (repo-create-checkbox url)))
  (browser click ::create-within-product)
  (browser getEval ::existing-product-dropdown)
  (browser mouseUp (existing-product-select (:name product)))
  (browser click ::create-repositories)
  (notification/check-for-success {:match-pred (notification/request-type? :repo-create)})) 

(extend katello.Provider
  ui/CRUD {:create create
           :delete delete
           :update edit}

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

  nav/Destination {:go-to (fn [prov]
                            (nav/go-to ::named-page {:org (:org prov)
                                                     :provider prov }))})

(extend katello.Product
  ui/CRUD {:create add-product
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
               :read (partial rest/read-impl id-url)
               })

  tasks/Uniqueable  tasks/entity-uniqueable-impl

  nav/Destination {:go-to (fn [{:keys [provider name] :as product}]
                            (nav/go-to ::named-product-page {:org (:org provider)
                                                             :provider name
                                                             :product-name (:name product)}))})
