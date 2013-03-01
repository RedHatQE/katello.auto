(ns katello.providers
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            katello
            (katello [navigation :as nav]
                     [notifications :as notification] 
                     [ui :as ui]
                     [rest :as rest]
                     [ui-common :as common])))

;; Locators

(ui/deflocators
       {::new                       "new"
        ::name-text                 "provider[name]"
        ::description-text          "provider[description]"
        ::repository-url-text       "provider[repository_url]"
        ::create-save               "provider_submit"
        ::remove-provider-link      (ui/remove-link "providers")
        ::products-and-repositories (ui/menu-link "products_repos")
        ::details-link              (ui/menu-link "edit_custom_providers")}

       ;;products
       {::add-product              (ui/button-div "Add Product")
        ::create-product           "//input[@value='Create']"
        ::product-name-text        "//*[@name='product[name]']"
        ::product-label-text       "//*[@name='product[label]']"
        ::product-description-text "//*[@name='product[description]']"
        ::remove-product           (ui/remove-link "products")}
       ui/locators)

;; Nav

(nav/defpages (common/pages)
  [::custom-page
   [::new-page [] (browser click ::new)]
   [::named-page [provider-name] (nav/choose-left-pane  provider-name)
    [::products-page [] (sel/->browser (click ::products-and-repositories)
                                       (sleep 2000))
     [::named-product-page [product-name] (browser click (ui/editable product-name))]]
    [::details-page [] (browser click ::details-link)]]])

;; Tasks

(defn create
  "Creates a custom provider with the given name and description."
  [{:keys [name description]}]
  (nav/go-to ::new-page)
  (sel/fill-ajax-form {::name-text name
                       ::description-text description}
                      ::create-save)
  (notification/check-for-success {:match-pred (notification/request-type? :prov-create)}))

(defn add-product
  "Adds a product to a provider, with the given name and description."
  [{:keys [provider-name name description]}]
  (nav/go-to ::products-page {:provider-name provider-name})
  (browser click ::add-product)
  (sel/fill-ajax-form {::product-name-text name
                       ::product-description-text description}
                      ::create-product)
  (notification/check-for-success {:match-pred (notification/request-type? :prod-create)}))

(defn delete-product
  "Deletes a product from the given provider."
  [{:keys [name provider]}]
  {:pre [(not-empty provider)]}
  (nav/go-to ::named-product-page {:provider-name (:name provider)
                                   :product-name name})
  (browser click ::remove-product)
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success {:match-pred (notification/request-type? :prod-destroy)}))

(defn delete
  "Deletes the named custom provider."
  [{:keys [name]}]
  (nav/go-to ::named-page {:provider-name name})
  (browser click ::remove-provider-link)
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success {:match-pred (notification/request-type? :prov-destroy)}))

(defn edit
  "Edits the named custom provider. Takes an optional new name, and
  new description."
  [{:keys [name] :as prov} f & args]
  (nav/go-to ::details-page {:provider-name name})
  (let [updated (apply f prov args)]
    (common/in-place-edit {::name-text (:name updated)
                           ::description-text (:description updated)})))

(extend katello.Provider
  ui/CRUD {:create create
           :delete delete
           :update edit}

  rest/CRUD (let [org-url (partial rest/url-maker [["api/organizations/%s/providers" [:org]]])
                 id-url (partial rest/url-maker [["api/providers/%s" [identity]]])]
             {:id rest/id-impl
              :query (partial rest/query-by-name org-url)
              :create (fn [{:keys [name description org] :as prov}]
                        (merge prov
                               (rest/post (rest/api-url "api/providers")
                                          {:body {:organization_id (rest/id org)
                                                  :provider {:name name
                                                             :description description
                                                             :provider_type "Custom"}}})))
              :read (partial rest/read-impl id-url)
              :update (fn [prov f & args]
                        (let [updated (apply f prov args)]
                          (merge updated (rest/put (id-url prov)
                                                   {:body {:provider
                                                           {:repository_url (:repository_url updated)}}}))))
              :delete (fn [prov] (rest/delete (id-url prov)))}))

(extend katello.Product
  ui/CRUD {:create add-product
           :delete delete-product}

  rest/CRUD (let [id-url (partial rest/url-maker [["api/organizations/%s/products/%s" [:org identity]]])
                 org-prod-url ["api/organizations/%s/products/%s" [:org identity]]
                 query-urls (partial rest/url-maker [["api/organizations/%s/products" [:org]]
                                                    ["/api/environments/%s/products" [:env]]])]
             {:id rest/id-impl
              :query (partial rest/query-by-name query-urls)
              :create (fn [prod]
                        (merge prod
                               (rest/post
                                (rest/url-maker [["api/providers/%s/product_create" [:provider]]] prod)
                                {:body {:product (select-keys prod [:name :description :gpg_key_name])}})))
              :read (partial rest/read-impl id-url)
              }))

