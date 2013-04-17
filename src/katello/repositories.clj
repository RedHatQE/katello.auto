(ns katello.repositories
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]] 
            [katello :as kt]
            (katello [tasks :as tasks]
                     [organizations :as organization]
                     [navigation :as nav]
                     [providers :as provider]        ;to load navigation
                     [notifications :as notification] 
                     [ui :as ui]
                     [rest :as rest])))

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
   [::named-page [repo] (browser click (ui/editable (:name repo)))]])

;; Tasks

(defn create
  "Adds a repository under the given provider and product. Requires a
   name and url be given for the repo."
  [{:keys [product name url gpg-key]}]
  (nav/go-to ::provider/products-page {:org (-> product :provider :org)
                                       :provider (:provider product)})
  (browser click (add-repo-link (:name product)))
  (when gpg-key (browser select ::repo-gpg-select (:name gpg-key)))
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

  rest/CRUD {:create (fn [{:keys [product name url]}]
                       (rest/http-post (rest/api-url "api/repositories/")
                                       {:body {:organization_id (-> product kt/org :name)
                                               :product_id (rest/get-id product)
                                               :name name
                                               :url url}}))
             :read (partial rest/read-impl (partial rest/url-maker [["api/repositories/%s" [identity]]]))
             :id rest/id-field
             :query (partial rest/query-by-name (partial rest/url-maker [["api/organizations/%s/products/%s/repositories" [kt/org kt/product]]]) )}
  
  tasks/Uniqueable  tasks/entity-uniqueable-impl

  nav/Destination {:go-to (fn [repo]
                            (nav/go-to ::named-page {:org (kt/org repo)
                                                     :provider (kt/provider repo)
                                                     :product (kt/product repo)
                                                     :repo repo}))})
