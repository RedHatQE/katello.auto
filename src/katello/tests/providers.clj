(ns katello.tests.providers
  (:refer-clojure :exclude [fn])
  (:require [katello.api-tasks :as api]
            [test.tree.script  :refer [deftest defgroup]]
            [serializable.fn   :refer [fn]]
            [test.assert       :as assert]
            [bugzilla.checker  :refer [open-bz-bugs]]
            (katello [tasks           :refer :all]
                     [api-tasks :as api]
                     [ui-common       :as common]
                     [notifications   :refer [success?]]
                     [organizations   :as organization]
                     [environments   :as env]
                     [sync-management :as sync] 
                     [repositories    :as repo]
                     [providers       :as provider]
                     [gpg-keys        :as gpg-key]
                     [package-filters :as filter]
                     [fake-content    :as fake]
                     [validation      :refer :all]
                     [conf            :refer [config]])))

;; Functions

(defn get-all-providers "Uses API to return all provider names in the admin org"
  []
  (map :name (api/all-entities :provider)))

(defn verify-provider-renamed
  "Verifies that a provider named old-name doesn't exist, that that a
  provider named new-name does exist."
  [old-name new-name]
  (let [current-provider-names (get-all-providers)]
    (assert/is (and (some #{new-name} current-provider-names)
                    (not (some #{old-name} current-provider-names))))))

(defn with-n-new-orgs
  "Create n organizations with unique names. Then calls function f
  with a unique name, and the org names. This is useful for verifying
  whether the same name for an entity can be used across orgs.
  Switches back to admin org after f is called."
  [n f]
  (let [ent-name (uniqueify "samename")
        orgs (take n (unique-names "ns-org"))]
    (doseq [org orgs]
      (api/create-organization org))
    (try
      (f ent-name orgs)
      (finally (organization/switch (@config :admin-org))))))

(defn with-two-providers
  "Create two providers with unique names, and call f with a unique
  entity name, and the provider names. Used for verifying (for
  instance) that products with the same name can be created in 2
  different providers."
  [f]
  (let [ent-name (uniqueify "samename")
        providers (take 2 (unique-names "ns-provider"))]
    (doseq [provider providers]
      (api/create-provider provider))
    (f ent-name providers)))

(defn create-same-provider-in-multiple-orgs
  "Create providers with the same name in multiple orgs."
  [prov-name orgs]
  (doseq [org orgs]
    (organization/switch org)
    (provider/create {:name prov-name})))

(defn validation
  "Attempts to create a provider and validates the result using
   pred."
  [provider pred]
  (expecting-error pred (provider/create provider)))

(defn get-validation-data
  []
  (concat
   [[{:name nil
      :description "blah"
      :url "http://sdf.com"} (common/errtype :katello.notifications/name-cant-be-blank)]

    [{:name (uniqueify "mytestcp4")
      :description nil
      :url "http://sdf.com"} success?]]

   (for [js-str javascript-strings]
     [{:name (uniqueify "mytestcp5")
       :description js-str
       :url "http://sdf.com"}  success?])
    
   (for [trailing-ws-str trailing-whitespace-strings]
     [{:name trailing-ws-str
       :description nil
       :url "http://sdf.com"} (common/errtype :katello.notifications/name-no-leading-trailing-whitespace)])
    
   (for [inv-char-str invalid-character-strings]
     [{:name inv-char-str
       :description nil
       :url "http://sdf.com"} (common/errtype :katello.notifications/name-must-not-contain-characters)])))

(defn setup-custom-providers-with-gpg-keys [gpg-key-name url]
  (let [provider-name (uniqueify "custom_provider")
        product-name (uniqueify "custom_product")
        repo-name (uniqueify "zoo_repo")
        product-name2 (uniqueify "custom_product2")
        repo-name2 (uniqueify "zoo_repo2")]
    (provider/create {:name provider-name})
    (provider/add-product {:provider-name provider-name
                           :name product-name})
    (repo/add-with-key {:provider-name provider-name
                        :product-name product-name
                        :name repo-name
                        :url url
                        :gpgkey gpg-key-name})
    (provider/add-product {:provider-name provider-name
                           :name product-name2})
    (repo/add-with-key {:provider-name provider-name
                        :product-name product-name2
                        :name repo-name2
                        :url url
                        :gpgkey gpg-key-name})
    (assert/is (every? true? (for [reponame [repo-name repo-name2]]
                               (gpg-key/gpg-keys-prd-association? gpg-key-name reponame))))))
  

(defn create-gpg-key-with-products [gpg-key-type gpg-key gpg-key-name]
  ;; gpg-key is a string when type is content and 
  ;; gpg-key is a url when type is url
  (let [org-name   (@config :admin-org)
        zoo-url    (-> fake/custom-providers first :products first :repos second :url) 
        safari-url (-> fake/custom-provider first :products first :repos first :url)]
    (organization/switch org-name)
    (api/ensure-env-exist (first (@config :environments)) {:prior library})
    (gpg-key/create gpg-key-name {(keyword gpg-key-type) gpg-key})
    (setup-custom-providers-with-gpg-keys gpg-key-name zoo-url)
    (setup-custom-providers-with-gpg-keys gpg-key-name safari-url)))

(defn upload-gpg-key-to-multiple-orgs [gpg-key gpg-key-name]
  (let [test-org (uniqueify "custom-org")
        org-name (@config :admin-org)
        url      (-> fake/custom-providers first :products first :repos second :url)
        envz     (take 3 (unique-names "env3"))]
    (organization/switch org-name)
    (api/ensure-env-exist (first (@config :environments)) {:prior library})
    (gpg-key/create gpg-key-name {:contents gpg-key})
    (setup-custom-providers-with-gpg-keys gpg-key-name url)
    (organization/create test-org)
    (organization/switch test-org)
    (env/create-path test-org envz)
    (gpg-key/create gpg-key-name {:contents gpg-key})
    (setup-custom-providers-with-gpg-keys gpg-key-name url)
    (organization/switch org-name)))

;; Tests

(defgroup gpg-key-tests
  
  (deftest "Create a new GPG key from text input"
    :blockers api/katello-only
    
    (with-unique [test-key "test-key-text"]
      (gpg-key/create test-key {:contents (slurp (@config :gpg-key))})))
  
  (deftest "Create a new GPG key from file"
    :blockers (open-bz-bugs "835902" "846432")

    (with-unique [test-key "test-key-file"]
      (gpg-key/create test-key {:url (@config :gpg-key)}))
    
    (deftest "Delete existing GPG key" 
      (with-unique [test-key "test-key-del"]
        (gpg-key/create test-key {:url (@config :gpg-key) })
        (gpg-key/remove test-key))))
    
  (deftest "Create a new GPG key from text input and associate it with products/providers"
    :blockers api/katello-only
    
    (let  [gpg-key (slurp (@config :gpg-key))
           gpg-key-name (uniqueify "test-key-text2")]
      (create-gpg-key-with-products "contents" gpg-key gpg-key-name)))
  
  (deftest "Create a new GPG key from file and associate it with products/providers"
    :blockers api/katello-only

    (let  [gpg-key-name (uniqueify "test-key-file2")
           gpg-key-url  (@config :gpg-key)]
      (create-gpg-key-with-products "url" gpg-key-url gpg-key-name)))
  
  (deftest "Delete existing GPG key, associated with products/providers"
    :blockers api/katello-only
    
    (let [gpg-key      (slurp (@config :gpg-key))
          gpg-key-name (uniqueify "test-key-del")]
      (create-gpg-key-with-products "contents" gpg-key gpg-key-name)
      (gpg-key/remove gpg-key-name)))
  
  (deftest "Associate same GPG key to multiple orgs"
    :blockers api/katello-only
    
    (let [gpg-key      (slurp (@config :gpg-key))
          gpg-key-name (uniqueify "test-key-multiorg")]
      (upload-gpg-key-to-multiple-orgs gpg-key gpg-key-name))))


(defgroup package-filter-tests

  (deftest "Create new Package Filter test"
    (with-unique [test-package-filter "test-package-filter"]
      (filter/create test-package-filter {:description "Test filter"}))
    
    (deftest "Delete existing Package Filter test" 
      (with-unique [test-package-filter "test-package-filter"]
        (filter/create test-package-filter {:description "Test filter"})
        (filter/remove test-package-filter)))))


(defgroup provider-tests
  :test-setup organization/before-test-switch
  
  (deftest "Create a custom provider" 
    (provider/create {:name (uniqueify "auto-cp")
                      :description "my description"})


    (deftest "Cannot create two providers in the same org with the same name"
      (with-unique [provider-name "dupe"]
        (expecting-error-2nd-try duplicate-disallowed
          (provider/create {:name provider-name
                            :description "mydescription"}))))
    
    (deftest "Provider validation"
      :data-driven true
      :description "Creates a provider using invalid data, and
                    verifies that an error notification is shown in
                    the UI."
      validation
      (get-validation-data))

    
    (deftest "Rename a custom provider"
      (with-unique [old-name  "rename"
                    new-name  "newname"]
        (provider/create {:name old-name :description "my description"})
        (provider/edit {:name old-name :new-name new-name})
        (verify-provider-renamed old-name new-name)))
    
    
    (deftest "Delete a custom provider"
      (with-unique [provider-name "auto-provider-delete"]
        (provider/create {:name provider-name :description "my description"})
        (provider/delete provider-name)))

    
    (deftest "Create two providers with the same name, in two different orgs"
      (with-n-new-orgs 2 create-same-provider-in-multiple-orgs)))

  gpg-key-tests
  package-filter-tests)
