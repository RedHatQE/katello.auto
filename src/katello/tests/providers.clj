(ns katello.tests.providers
  (:refer-clojure :exclude [fn])
  (:require [katello.api-tasks :as api]
            [clj-http.client :as http]
            [clojure.java.io :as io]
            [slingshot.slingshot :refer :all]
            [test.tree.script :refer :all]
            [test.tree.builder :refer [data-driven]]
            [serializable.fn :refer [fn]]
            [tools.verify :refer [verify-that]]
            [bugzilla.checker :refer [open-bz-bugs]]
            [katello.tests.e2e :refer [test-client-access]] 
            (katello [tasks :refer :all]
                     [notifications :refer [success?]] 
                     [organizations :as organization] 
                     [changesets :refer [sync-and-promote]] 
                     [sync-management :as sync] 
                     [systems :refer [edit-system]] 
                     [providers :as provider]
                     [ui-tasks :refer :all]
                     [validation :refer :all]
                     [conf :refer [config no-clients-defined]])))

;; Constants

(def tmpfile (str (System/getProperty "user.dir") "/output.txt"))

;; Functions

(defn get-all-providers "Uses API to return all provider names in the admin org"
  []
  (map :name (api/with-admin (api/all-entities :provider))))

(defn verify-provider-renamed
  "Verifies that a provider named old-name doesn't exist, that that a
  provider named new-name does exist."
  [old-name new-name]
  (let [current-provider-names (get-all-providers)]
    (verify-that (and (some #{new-name} current-provider-names)
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
      (api/with-admin (api/create-organization org)))
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
      (api/with-admin (api/create-provider provider)))
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
      :url "http://sdf.com"} (errtype :katello.notifications/name-cant-be-blank)]

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
       :url "http://sdf.com"} (errtype :katello.notifications/name-no-leading-trailing-whitespace)])
    
   (for [inv-char-str invalid-character-strings]
     [{:name inv-char-str
       :description nil
       :url "http://sdf.com"} (errtype :katello.notifications/name-must-not-contain-characters)])))

;; Tests

;; Load more tests groups into this namespace
(load "providers/custom-product")
(load "providers/redhat")


(defgroup gpg-key-tests

  (deftest "Create a new GPG key from text input"
    (with-unique [test-key "test-key-text"]
      (create-gpg-key test-key {:contents "asdfasdfasdfasdfasdfasdfasdf"})))
  
  (deftest "Create a new GPG key from file"
    :blockers (open-bz-bugs "835902" "846432")

    (with-unique [test-key "test-key"]
      (spit "output.txt" "test")
      (create-gpg-key test-key {:filename tmpfile}))

    
    (deftest "Delete existing GPG key" 
      (with-unique [test-key "test-key"]
        (spit "output.txt" "test")
        (create-gpg-key test-key {:filename tmpfile})
        (remove-gpg-key test-key)))))


(defgroup package-filter-tests

  (deftest "Create new Package Filter test"
    (with-unique [test-package-filter "test-package-filter"]
      (katello.ui-tasks/create-package-filter test-package-filter {:description "Test filter"}))
    
    (deftest "Delete existing Package Filter test" 
      (with-unique [test-package-filter "test-package-filter"]
        (katello.ui-tasks/create-package-filter test-package-filter {:description "Test filter"})
        (katello.ui-tasks/remove-package-filter test-package-filter)))))


(defgroup provider-tests
  
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
      (with-n-new-orgs 2 create-same-provider-in-multiple-orgs))

    custom-product-tests)
  
  redhat-content-provider-tests
  gpg-key-tests
  package-filter-tests
  redhat-provider-one-org-multiple-manifest-tests
  redhat-provider-second-org-one-manifest-tests
  redhat-provider-used-manifest-tests
  redhat-provider-other-manifest-tests)


