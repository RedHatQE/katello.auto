(ns katello.repositories
  (:require [webdriver :as browser]
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
  {::repositories-link         "//nav[@class='details-navigation']//a[contains(.,'Repositories')]"
   ::create-repo               "//button[contains(@class,'ng-binding') and contains(.,'Create Repository')]"       
   ::repo-name-text            "//input[@name='name']"
   ::repo-label-text           "//input[@name='label']"
   ::repo-type-select          "//select[@name='content_type']"
   ::repo-url-text             "//input[@name='url']"
   ::repo-protection-checkbox  "//input[@name='unprotected']"   
   ::repo-gpg-select           "//select[@name='gpg_key_id']"
   ::repo-save                 "//form[@name='repositoryForm']//span[@class='ng-binding' and contains(.,'Create')]"
   ::repo-remove               "//button[contains(@class,'ng-binding') and contains(.,'Remove Repository')]"
   ::confirm-repo-rm           "//div[@alch-confirm-modal='removeRepository(repository)']//button[contains(.,'Yes')]"
   ::repo-list                 "//a[@class='ng-binding' and contains(.,'Back to Repository List')]"
   
   ::repo-gpgkey-update        "//div[@selector='repository.gpg_key_id']//i[contains(@class,'icon-edit')]"
   ::repo-gpgkey-update-select "//div[@selector='repository.gpg_key_id']//select[@ng-model='selector']"
   ::save-updated-gpg-key     "//div[@selector='repository.gpg_key_id']//button[contains(.,'Save')]"})

(browser/template-fns
 {select-repository            "//a[contains(@href,'repositories') and contains(.,'%s')]"
  gpgkey-under-repo-details    "//span[contains(@class,'ng-binding') and normalize-space(.)='%s']"})

(nav/defpages :katello.deployment/any katello.providers
  [::provider/products-page
   [::product-page (fn [ent] (browser/click (provider/select-product (:name (kt/product ent)))))
    [::product-repo-page (fn [_] (browser/click ::repositories-link))
     [::repo-page (fn [ent] (browser/click (select-repository (:name ent))))]]]])

;; Tasks

(defn- create
  "Adds a repository under the product. 
   Requires a name and url be given for the repo."
  [{:keys [product name url gpg-key repo-type http?]}]
   {:pre [(instance? katello.Product product)
          (instance? katello.Provider (kt/provider product))
          (instance? katello.Organization (kt/org product))]} 
  (nav/go-to ::product-page product) 
  (browser/click ::create-repo)
  (when gpg-key (browser/select-by-text ::repo-gpg-select (:name gpg-key)))
  (browser/select-by-text ::repo-type-select repo-type)
  (when http? (browser/click ::repo-protection-checkbox))
  (browser/input-text ::repo-name-text name)
  (when url (browser/input-text ::repo-url-text url))
  (browser/click ::repo-save))

(defn- update
  "Edits a repository. Currently the only property of a repository that
   can be edited is the gpg-key associated."
  [repo {:keys [gpg-key]}]
  (when (not= (:gpg-key repo) gpg-key)
    (nav/go-to repo)
    (browser/click  ::repo-gpgkey-update)
    (browser/select-by-text ::repo-gpgkey-update-select gpg-key)
    (browser/click  ::save-updated-gpg-key)))
  

(defn- delete "Deletes a repository from the given product."
  [repo]
  {:pre [(instance? katello.Repository repo)]}
  (nav/go-to repo)
  (when (browser/displayed? ::repo-remove)
     (browser/click ::repo-remove)
     (browser/click ::confirm-repo-rm)))

(defn gpgkey-associated?
  [repo]
  (nav/go-to repo)
  (browser/exists? (gpgkey-under-repo-details (-> repo kt/product :gpg-key :name))))


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

  nav/Destination {:go-to (partial nav/go-to ::repo-page)})
