(ns katello.providers
  (:require [com.redhat.qe.auto.selenium.selenium :as sel]
            [com.redhat.qe.auto.selenium.selenium :refer [browser]] 
            (katello [navigation :as nav]
                     [notifications :as notification] 
                     [ui :as ui]
                     [ui-common :as common])))

;; Locators

(swap! ui/locators merge
       {::new                       "new"
        ::name-text                 "provider[name]"
        ::description-text          "provider[description]"
        ::repository-url-text       "provider[repository_url]"
        ::cert-text                 (ui/textbox "provider[certificate_attributes][contents]")
        ::create-save               "provider_submit"
        ::provider                  (ui/link "Remove Provider")
        ::subscriptions             (ui/link "Subscriptions")
        ::import-manifest           "new"
        ::choose-file               "provider_contents"
        ::force-import-checkbox     "force_import"
        ::products-and-repositories "//nav[contains(@class,'subnav')]//a[contains(.,'Products')]"
        ::subscriptions-items       "//table[@id='redhatSubscriptionTable']/tbody/tr"
        ::details-link              (ui/menu-link "edit_custom_providers")}

       ;;products
       {::add-product              (ui/button-div "Add Product")
        ::create-product           "//input[@value='Create']"
        ::product-name-text        "//*[@name='product[name]']"
        ::product-label-text       "//*[@name='product[label]']"
        ::product-description-text "//*[@name='product[description]']"
        ::remove-product           (ui/link "Remove Product")})

(nav/add-subnavigation
 ::custom-page
 [::new-page [] (browser click ::new)]
 [::named-page [provider-name] (nav/choose-left-pane  provider-name)
  [::details-page [] (browser click ::details-link)]])

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
  (nav/go-to ::products-repos-page {:provider-name provider-name})
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
  (browser click ::provider)
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success {:match-pred (notification/request-type? :prov-destroy)}))

(defn edit
  "Edits the named custom provider. Takes an optional new name, and
  new description." [{:keys [name new-name description]}]
  (nav/go-to ::details-page {:provider-name name})
  (common/in-place-edit {::name-text new-name
                         ::description-text description}))
