(ns katello.providers
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]] 
            (katello [navigation :as nav]
                     [notifications :as notification] 
                     [ui :as ui]
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
  [{:keys [name provider-name]}]
  (nav/go-to ::named-product-page {:provider-name provider-name
                                   :product-name name})
  (browser click ::remove-product)
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success {:match-pred (notification/request-type? :prod-destroy)}))

(defn delete
  "Deletes the named custom provider."
  [name]
  (nav/go-to ::named-page {:provider-name name})
  (browser click ::remove-provider-link)
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success {:match-pred (notification/request-type? :prov-destroy)}))

(defn edit
  "Edits the named custom provider. Takes an optional new name, and
  new description." [{:keys [name new-name description]}]
  (nav/go-to ::details-page {:provider-name name})
  (common/in-place-edit {::name-text new-name
                         ::description-text description}))
