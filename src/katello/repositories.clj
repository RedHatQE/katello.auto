(ns katello.repositories
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]] 
            katello
            (katello [tasks :as tasks]
                     [organizations :as organization]
                     [navigation :as nav]
                     [providers :as provider]        ;to load navigation
                     [notifications :as notification] 
                     [ui :as ui])))

;; Locators

(ui/deflocators
  {::repo-name-text    "repo[name]"
   ::repo-label-text   "repo[label]"
   ::repo-url-text     "repo[feed]" 
   ::save-repository   "//input[@value='Create']"
   ::remove-repository (ui/link "Remove Repository")
   ::repo-gpg-select   "//select[@id='repo_gpg_key']"}
  ui/locators)

(sel/template-fns
 {repo-enable-checkbox "//table[@id='products_table']//label[normalize-space(.)='%s']/..//input"
  add-repo-link "//div[@id='products']//div[contains(.,'%s')]/..//div[normalize-space(.)='Add Repository' and contains(@class, 'button')]"})

(nav/defpages (provider/pages)
  [::provider/products-page 
   [::named-page [repo-name] (browser click (ui/editable repo-name))]])

;; Tasks

(defn create
  "Adds a repository under the given provider and product. Requires a
   name and url be given for the repo."
  [{:keys [product name url gpg-key]}]
  (nav/go-to ::provider/products-page {:provider-name (-> product :provider :name)})
  (browser click (add-repo-link (:name product)))
  (when gpg-key (browser select ::repo-gpg-select gpg-key))
  (sel/fill-ajax-form {::repo-name-text name
                       ::repo-url-text url}
                      ::save-repository)
  (notification/check-for-success {:match-pred (notification/request-type? :repo-create)}))

(defn delete "Deletes a repository from the given provider and product."
  [repo]
  (nav/go-to repo)
  (browser click ::remove-repository)
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success {:match-pred (notification/request-type? :repo-destroy)}))

(defn enable-redhat
  "Enable the given list of repos in the current org."
  [repos]
  (nav/go-to ::redhat-page)
  (doseq [repo repos]
    (browser check (repo-enable-checkbox (:name repo)))))


(extend katello.Repository
  ui/CRUD {:create create
           :delete delete}
  
  tasks/Uniqueable  tasks/entity-uniqueable-impl

  nav/Destination {:go-to (fn [{:keys [product name]}]
                            (organization/switch (-> product :provider :org))
                            (nav/go-to ::named-page {:provider-name  (-> product :provider :name)
                                                     :product-name (:name product)
                                                     :repo-name name}))})
