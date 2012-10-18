(ns katello.providers
  (:require [com.redhat.qe.auto.selenium.selenium :refer [browser]] 
            (katello [locators :as locators] 
                     [notifications :refer [check-for-success notifications]] 
                     [ui-tasks :refer [navigate fill-ajax-form in-place-edit]])))

;;
;; Providers
;;

(defn create
  "Creates a custom provider with the given name and description."
  [{:keys [name description]}]
  (navigate :new-provider-page)
  (fill-ajax-form {:provider-name-text name
                   :provider-description-text description}
                  :provider-create-save)
  (check-for-success))

(defn add-product
  "Adds a product to a provider, with the given name and description."
  [{:keys [provider-name name description]}]
  (navigate :provider-products-repos-page {:provider-name provider-name})
  (browser click :add-product)
  (fill-ajax-form {:product-name-text name
                  :product-description-text description}
                  :create-product)
  (check-for-success))

(defn delete-product
  "Deletes a product from the given provider."
  [{:keys [name provider-name]}]
  (navigate :named-product-page {:provider-name provider-name
                                 :product-name name})
  (browser click :remove-product)
  (browser click :confirmation-yes)
  (check-for-success))

(defn add-repo
  "Adds a repository under the given provider and product. Requires a
   name and url be given for the repo."
  [{:keys [provider-name product-name name url]}]
  (navigate :provider-products-repos-page {:provider-name provider-name})
  (browser click (locators/add-repository product-name))
  (fill-ajax-form {:repo-name-text name
                   :repo-url-text url}
                  :save-repository)
  (check-for-success))

(defn delete-repo
  "Deletes a repository from the given provider and product."
  [{:keys [name provider-name product-name]}]
  (navigate :named-repo-page {:provider-name provider-name
                              :product-name product-name
                              :repo-name name})
  (browser click :remove-repository)
  (browser click :confirmation-yes)
  (check-for-success))

(defn delete
  "Deletes the named custom provider."
  [name]
  (navigate :named-provider-page {:provider-name name})
  (browser click :remove-provider)
  (browser click :confirmation-yes)
  (check-for-success))

(defn edit
  "Edits the named custom provider. Takes an optional new name, and
  new description." [{:keys [name new-name description]}]
  (navigate :provider-details-page {:provider-name name})
  (in-place-edit {:provider-name-text new-name
                  :provider-description-text description}))
