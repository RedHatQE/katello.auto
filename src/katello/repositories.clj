(ns katello.repositories
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]] 
            (katello [navigation :as nav]
                     providers ;to load navigation
                     [notifications :as notification] 
                     [ui :as ui])))

;; Locators

(swap! ui/locators merge
       {::repo-name-text    "repo[name]"
        ::repo-label-text   "repo[label]"
        ::repo-url-text     "repo[feed]" 
        ::save-repository   "//input[@value='Create']"
        ::remove-repository (ui/link "Remove Repository")
        ::repo-gpg-select   "//select[@id='repo_gpg_key']"})

(sel/template-fns
 {repo-enable-checkbox "//table[@id='products_table']//label[normalize-space(.)='%s']/..//input"
  add-repo-link "//div[@id='products']//div[contains(.,'%s')]/..//div[normalize-space(.)='Add Repository' and contains(@class, 'button')]"})

(nav/add-subnavigation
 :katello.providers/named-page
 [::products-repos-page [] (sel/->browser (click ::products-and-repositories)
                                          (sleep 2000))
  [::named-product-page [product-name] (browser click (ui/editable product-name))]
  [::named-repo-page [product-name repo-name] (browser click (ui/editable repo-name))]])

;; Tasks



(defn add
  "Adds a repository under the given provider and product. Requires a
   name and url be given for the repo."
  [{:keys [provider-name product-name name url]}]
  (nav/go-to ::products-repos-page {:provider-name provider-name})
  (browser click (add-repo-link product-name))
  (sel/fill-ajax-form {::repo-name-text name
                       ::repo-url-text url}
                      ::save-repository)
  (notification/check-for-success {:match-pred (notification/request-type? :repo-create)}))

(defn add-with-key
  "Adds a repository under the given provider and product. Requires a
   name and url be given for the repo."
  [{:keys [provider-name product-name name url gpgkey]}]
  (nav/go-to ::products-repos-page {:provider-name provider-name})
  (browser click (add-repo-link product-name))
  (browser select ::repo-gpg-select gpgkey)
  (sel/fill-ajax-form {::repo-name-text name
                       ::repo-url-text url}
                      ::save-repository)
  (notification/check-for-success {:match-pred (notification/request-type? :repo-create)}))

(defn delete "Deletes a repository from the given provider and product."
  [{:keys [name provider-name product-name]}]
  (nav/go-to ::named-repo-page {:provider-name provider-name
                                :product-name product-name
                                :repo-name name})
  (browser click ::remove-repository)
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success {:match-pred (notification/request-type? :repo-destroy)}))

(defn enable-redhat
  "Enable the given list of repos in the current org."
  [repos]
  (nav/go-to ::redhat-page)
  (doseq [repo repos]
    (browser check (repo-enable-checkbox repo))))