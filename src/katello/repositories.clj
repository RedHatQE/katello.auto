(ns katello.repositories
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]] 
            (katello [navigation :as nav]
                     [providers :as provider]        ;to load navigation
                     [notifications :as notification] 
                     [ui :as ui])))

;; Locators

(ui/deflocators
  {::repo-name-text         "repo[name]"
   ::repo-label-text        "repo[label]"
   ::repo-url-text          "repo[feed]"
   ::save-repository        "//input[@value='Create']"
   ::remove-repository      (ui/link "Remove Repository")
   ::repo-gpg-select        "//select[@id='repo_gpg_key']"
   ::add-repo-button        "//div[contains(@class,'button') and contains(.,'Add Repository')]"   
   ::repo-discovery         "//a[contains(@href, 'repo_discovery')]"
   ::discover-url-text      "discover_url"
   ::discover-button        "//input[@type='submit']"
   ::discover-cancel-button "//*[@class='grid_2 la' and @style='display: none;']"}
  ui/locators)

(sel/template-fns
 {repo-enable-checkbox "//table[@id='products_table']//label[normalize-space(.)='%s']/..//input"
  add-repo-link "//div[@id='products']//div[contains(.,'%s')]/..//div[normalize-space(.)='Add Repository' and contains(@class, 'button')]"})

(nav/defpages (provider/pages)
  [::provider/products-page 
   [::named-page [product-name repo-name] (browser click (ui/editable repo-name))]])

;; Tasks

(defn add
  "Adds a repository under the given provider and product. Requires a
   name and url be given for the repo."
  [{:keys [provider-name product-name name url]}]
  (nav/go-to ::provider/products-page {:provider-name provider-name})
  (browser click (add-repo-link product-name))
  (sel/fill-ajax-form {::repo-name-text name
                       ::repo-url-text url}
                      ::save-repository)
  (notification/check-for-success {:match-pred (notification/request-type? :repo-create)}))

(defn add-with-url-autodiscovery
  "Adds auto-discovered repositories based on the url passed."
  [{:keys [provider-name product-name url]}]
  (nav/go-to ::provider/products-page {:provider-name provider-name})
  (browser click ::repo-discovery)
  (browser setText ::discover-url-text url)
  (browser click ::discover-button)
  (browser waitForElement ::discover-cancel-button "10000"))

(defn add-with-key
  "Adds a repository under the given provider and product. Requires a
   name and url be given for the repo."
  [{:keys [provider-name product-name name url gpgkey]}]
  (nav/go-to ::provider/products-page {:provider-name provider-name})
  (browser click (add-repo-link product-name))
  (browser select ::repo-gpg-select gpgkey)
  (sel/fill-ajax-form {::repo-name-text name
                       ::repo-url-text url}
                      ::save-repository)
  (notification/check-for-success {:match-pred (notification/request-type? :repo-create)}))

(defn delete "Deletes a repository from the given provider and product."
  [{:keys [name provider-name product-name]}]
  (nav/go-to ::named-page {:provider-name provider-name
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
