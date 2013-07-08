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

(ui/defelements :katello.deployment/any [katello.ui]
  {::repo-name-text         "repo[name]"
   ::repo-label-text        "repo[label]"
   ::repo-url-text          "repo[feed]"
   ::save-repository        "//input[@value='Create']"
   ::remove-repository      (ui/link "Remove Repository")
   ::repo-gpg-select        "//select[@id='repo_gpg_key']"
   ::update-repo-gpg-select "//select[@name='gpg_key']"
   ::update-gpg-key         "//div[@class='jspPane' and contains(.,'Repository Details')]//div[@name='gpg_key']"
   ::save-updated-gpg-key   "//div[@name='gpg_key']//button[contains(.,'Save')]"
   ::add-repo-button        "//div[contains(@class,'button') and contains(.,'Add Repository')]"   
   ::repo-discovery         "//a[contains(@href, 'repo_discovery')]"
   ::discover-url-text      "discover_url"
   ::discover-button        "//input[@type='submit']"
   ::discover-cancel-button "//*[@class='grid_2 la' and @style='display: none;']"}
  )

(sel/template-fns
 {repo-enable-checkbox "//table[@id='products_table']//label[normalize-space(.)='%s']/..//input"
  add-repo-link "//div[@id='products']//div[contains(.,'%s')]/..//div[normalize-space(.)='Add Repository' and contains(@class, 'button')]"
  gpgkey-under-repo-details "//div[@name='gpg_key' and contains(.,'%s')]"
  select-repo "//li[@class='repo']//div[contains(@class,'grid') and contains(.,'%s')]"})

(nav/defpages :katello.deployment/any katello.providers
  [::provider/products-page 
   [::named-page (fn [repo] (browser click (ui/editable (:name repo))))]])

;; Tasks

(defn- create
  "Adds a repository under the given provider and product. Requires a
   name and url be given for the repo."
  [{:keys [product name url gpg-key]}]
   {:pre [(instance? katello.Product product)
          (instance? katello.Provider (kt/provider product))
          (instance? katello.Organization (kt/org product))]} 

  (nav/go-to ::provider/products-page product)
  (browser click (add-repo-link (:name product)))
  (when gpg-key (browser select ::repo-gpg-select (:name gpg-key)))
  (sel/fill-ajax-form {::repo-name-text name
                       ::repo-url-text url}
                      ::save-repository)
  (notification/success-type :repo-create))

(defn- update
  "Edits a repository. Currently the only property of a repository that
   can be edited is the gpg-key associated."
  [repo {:keys [gpg-key]}]
  (when (not= (:gpg-key repo) gpg-key)
    (nav/go-to repo)
    (sel/->browser (click  ::update-gpg-key)
                   (select ::update-repo-gpg-select gpg-key)
                   (click  ::save-updated-gpg-key))
    (notification/success-type :repo-update-gpg-key)))
  

(defn- delete "Deletes a repository from the given provider and product."
  [repo]
  {:pre [(instance? katello.Repository repo)]}
  (nav/go-to repo)
  (browser click ::remove-repository)
  (browser click ::ui/confirmation-yes)
  (notification/success-type :repo-destroy))

(defn gpgkey-associated?
  [product repo-name]
  (nav/go-to product)
  (browser click (select-repo repo-name))
  (browser isElementPresent (gpgkey-under-repo-details (:gpg-key product))))


(extend katello.Repository
  ui/CRUD {:create create
           :update* update
           :delete delete}

  rest/CRUD {:create (fn [{:keys [product name url]}]
                       {:pre [(instance? katello.Product product)
                              (instance? katello.Provider (kt/provider product))
                              (instance? katello.Organization (kt/org product))]} 
                       (rest/http-post (rest/api-url "api/repositories/")
                                       {:body {:organization_id (-> product kt/org :name)
                                               :product_id (rest/get-id product)
                                               :name name
                                               :url url}}))
             :read (partial rest/read-impl (partial rest/url-maker [["api/repositories/%s" [identity]]]))
             :id rest/id-field
             :query (partial rest/query-by-name (partial rest/url-maker [["api/organizations/%s/products/%s/repositories" [kt/org kt/product]]]) )}
  
  tasks/Uniqueable  tasks/entity-uniqueable-impl

  nav/Destination {:go-to (partial nav/go-to ::named-page)}) 
