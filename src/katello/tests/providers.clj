(ns katello.tests.providers
  (:refer-clojure :exclude [fn])
  (:require [katello.api-tasks :as api]
            [clj-http.client :as http]
            [clojure.java.io :as io])
  (:use katello.tasks
        katello.ui-tasks
        katello.validation
        slingshot.slingshot
        [katello.tests.e2e :only [test-client-access]]
        test.tree.script
        [test.tree.builder :only [data-driven]]
        [serializable.fn :only [fn]]
        [tools.verify :only [verify-that]]
        [bugzilla.checker :only [open-bz-bugs]]
        [katello.conf :only [config no-clients-defined]]))

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
      (finally (switch-org (@config :admin-org))))))

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
    (switch-org org)
    (create-provider {:name prov-name})))

(defn validation
  "Attempts to creates a provider and validates the result using
   pred."
  [provider pred]
  (expecting-error pred (create-provider provider)))

(defn get-validation-data
  []
  (concat
   [[{:name nil
      :description "blah"
      :url "http://sdf.com"} (errtype :katello.ui-tasks/name-cant-be-blank)]

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
       :url "http://sdf.com"} (errtype :katello.ui-tasks/name-no-leading-trailing-whitespace)])
    
   (for [inv-char-str invalid-character-strings]
     [{:name inv-char-str
       :description nil
       :url "http://sdf.com"} (errtype :katello.ui-tasks/name-must-not-contain-characters)])))

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
    (create-provider {:name (uniqueify "auto-cp")
                      :description "my description"})


    (deftest "Cannot create two providers in the same org with the same name"
      (with-unique [provider-name "dupe"]
        (expecting-error-2nd-try duplicate-disallowed
          (create-provider {:name provider-name
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
        (create-provider {:name old-name :description "my description"})
        (edit-provider {:name old-name :new-name new-name})
        (verify-provider-renamed old-name new-name)))
    
    
    (deftest "Delete a custom provider"
      (with-unique [provider-name "auto-provider-delete"]
        (create-provider {:name provider-name :description "my description"})
        (delete-provider provider-name)))

    
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


