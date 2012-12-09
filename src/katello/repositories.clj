(ns katello.repositories
  (:require [com.redhat.qe.auto.selenium.selenium :as sel]
            [com.redhat.qe.auto.selenium.selenium :refer [browser]] 
            (katello [navigation :as nav]
                     [notifications :as notification] 
                     [ui :as ui]
                     [ui-common :as common])))
(alias 'menu 'katello.menu)

;; Locators

(swap! ui/locators merge
       {::provider-new                       "new"
        ::provider-name-text                 "provider[name]"
        ::provider-description-text          "provider[description]"
        ::provider-repository-url-text       "provider[repository_url]"
        ::provider-cert-text                 (ui/textbox "provider[certificate_attributes][contents]")
        ::provider-create-save               "provider_submit"
        ::provider                  (ui/link "Remove Provider")
        ::subscriptions             (ui/link "Subscriptions")
        ::import-manifest           "new"
        ::choose-file               "provider_contents"
        ::upload                    "upload_form_button"
        ::force-import-checkbox     "force_import"
        ::products-and-repositories "//nav[contains(@class,'subnav')]//a[contains(.,'Products')]"
        ::subscriptions-items       "//table[@id='redhatSubscriptionTable']/tbody/tr"}

       ;;products
       {::add-product              (ui/button-div "Add Product")
        ::create-product           "//input[@value='Create']"
        ::product-name-text        "//*[@name='product[name]']"
        ::product-label-text       "//*[@name='product[label]']"
        ::product-description-text "//*[@name='product[description]']"
        ::remove-product           (ui/link "Remove Product")}

       ;;repos
       {::repo-name-text    "repo[name]"
        ::repo-label-text   "repo[label]"
        ::repo-url-text     "repo[feed]" 
        ::save-repository   "//input[@value='Create']"
        ::remove-repository (ui/link "Remove Repository")
        ::repo-gpg-select   "//select[@id='repo_gpg_key']"}

       ;;Package Filters
       {::create-new-package-filter      (ui/link "+ New Filter")
        ::new-package-filter-name        "filter[name]"
        ::new-package-filter-description "filter[description]"
        ::save-new-package-filter        "filter_submit"
        ::remove-package-filter-key      (ui/link "Remove Filter")})

(sel/template-fns
 {repo-enable-checkbox "//table[@id='products_table']//label[normalize-space(.)='%s']/..//input"
  add-repo "//div[@id='products']//div[contains(.,'%s')]/..//div[normalize-space(.)='Add Repository' and contains(@class, 'button')]"})

(nav/add-subnav-multiple
 ::custom-providers-page
 [::new-custom-provider-page [] (sel/browser click ::provider-new)]
 [::named-custom-provider-page [provider-name] (nav/choose-left-pane  provider-name)
  [::products-repos-page [] (sel/->browser (click ::products-and-repositories)
                                           (sleep 2000))
   [::named-product-page [product-name] (sel/browser click (ui/editable product-name))]
   [::named-repo-page [product-name repo-name] (sel/browser click (ui/editable repo-name))]]
  [::provider-details-page [] (sel/browser click :details)]])

(nav/add-subnav-multiple
 ::package-filters-page
 [::new-package-filter-page [] (sel/browser click ::create-new-package-filter)]
 [::named-package-filter-page [package-filter-name] (nav/choose-left-pane  package-filter-name)])

;; Tasks

(defn create
  "Creates a custom provider with the given name and description."
  [{:keys [name description]}]
  (nav/go-to ::new-custom-provider-page)
  (sel/fill-ajax-form {::provider-name-text name
                   ::provider-description-text description}
                  ::provider-create-save)
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

(defn add-repo
  "Adds a repository under the given provider and product. Requires a
   name and url be given for the repo."
  [{:keys [provider-name product-name name url]}]
  (nav/go-to ::products-repos-page {:provider-name provider-name})
  (browser click (add-repo product-name))
  (sel/fill-ajax-form {::repo-name-text name
                       ::repo-url-text url}
                      ::save-repository)
  (notification/check-for-success {:match-pred (notification/request-type? :repo-create)}))

(defn add-repo-with-key
  "Adds a repository under the given provider and product. Requires a
   name and url be given for the repo."
  [{:keys [provider-name product-name name url gpgkey]}]
  (nav/go-to ::products-repos-page {:provider-name provider-name})
  (browser click (add-repo product-name))
  (browser select ::repo-gpg-select gpgkey)
  (sel/fill-ajax-form {::repo-name-text name
                       ::repo-url-text url}
                      ::save-repository)
  (notification/check-for-success {:match-pred (notification/request-type? :repo-create)}))

(defn delete-repo
  "Deletes a repository from the given provider and product."
  [{:keys [name provider-name product-name]}]
  (nav/go-to ::named-repo-page {:provider-name provider-name
                                :product-name product-name
                                :repo-name name})
  (browser click ::remove-repository)
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success {:match-pred (notification/request-type? :repo-destroy)}))

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
  (nav/go-to ::provider-details-page {:provider-name name})
  (common/in-place-edit {::provider-name-text new-name
                  ::provider-description-text description}))

(defn enable-redhat-repositories
  "Enable the given list of repos in the current org."
  [repos]
  (nav/go-to ::redhat-page)
  (doseq [repo repos]
    (browser check (repo-enable-checkbox repo))))