(ns katello.tests.organizations
  (:refer-clojure :exclude [fn])
  (:require (katello [api-tasks :as api]
                     [validation :as v] 
                     [providers :as provider] 
                     [tasks :refer :all] 
                     [organizations :as organization] 
                     [ui-tasks :refer :all] 
                     [conf :refer [config]])
            [tools.verify :refer [verify-that]]
            [test.tree.script :refer :all] 
            [bugzilla.checker :refer [open-bz-bugs]]))

;; Functions

(defn create-test-org
  "Creates an organization named org-name via the API"
  [org-name]
  (api/with-admin-creds
    (api/create-organization org-name
                             {:description "organization used to test environments."})))

(defn get-all-org-names
  "Returns a list of the names of all the organizations in the system.
   Uses the API."
  []
  (doall (map :name
              (api/with-admin-creds
                (api/all-entities :organization)))))

(defn org-exists? [org-name]
  (some #{org-name} (get-all-org-names)))

(def org-does-not-exist? (complement org-exists?))

(defn verify-bad-org-name-gives-expected-error
  [name expected-error]
  (expecting-error (errtype expected-error) (organization/create name)))

(defn create-org-with-provider-and-repo [org-name provider-name product-name repo-name repo-url]
  (organization/create org-name {:description "org to delete and recreate"})
  (organization/switch org-name)
  (provider/create {:name provider-name
                     :description "provider to del and recreate"})
  (provider/add-product {:provider-name provider-name
                          :name product-name})
  (provider/add-repo {:name repo-name
                       :provider-name provider-name
                       :product-name product-name
                       :url repo-url}))

;; Data (Generated)

(def bad-org-names
  (concat
   (for [inv-char-str v/invalid-character-strings]
     [inv-char-str :katello.notifications/name-must-not-contain-characters])
   (for [trailing-ws-str v/trailing-whitespace-strings]
     [trailing-ws-str :katello.notifications/name-no-leading-trailing-whitespace])))

;; Tests

(defgroup org-tests

  (deftest "Create new organization via Manage Organizations link"
    (with-unique [org-name "managed-org"]
      (organization/create       org-name {:go-through-org-switcher true})
      (verify-that         (org-exists? org-name))))
  
  (deftest "Create an organization"
    (with-unique [org-name "auto-org"]
      (organization/create     org-name)
      (verify-that         (org-exists? org-name)))

    
    (deftest "Create an organization with an initial environment"
      (with-unique [org-name "auto-org"
                    env-name "environment"]
        (organization/create     org-name  {:initial-env-name env-name})
        (verify-that         (org-exists? org-name))))
  
    (deftest "Two organizations with the same name is disallowed"
      :blockers (open-bz-bugs "726724")
      
      (with-unique [org-name "test-dup"]
        (v/expecting-error-2nd-try (errtype :katello.notifications/name-taken-error)
                                   (organization/create org-name {:description "org-description"}))))

  
    (deftest "Organization name is required when creating organization"
      :blockers (open-bz-bugs "726724")
      
      (expecting-error v/name-field-required
                       (organization/create "" {:description "org with empty name"})))

    
    (deftest "Verify proper error message when invalid org name is used"
      :data-driven true
      :blockers (open-bz-bugs "726724")
      
      verify-bad-org-name-gives-expected-error
      bad-org-names)

  
    (deftest "Edit an organization"
      (with-unique [org-name "auto-edit"]
        (create-test-org     org-name)
        (organization/edit   org-name     :description     "edited description")))

    (deftest "Delete an organization"
      :blockers (open-bz-bugs "716972")
    
      (with-unique [org-name "auto-del"]
        (create-test-org     org-name)
        (organization/delete org-name)
        (verify-that         (org-does-not-exist? org-name)))

    
      (deftest "Create an org with content, delete it and recreate it"
        :blockers api/katello-only
        
        (with-unique [org-name       "delorg"
                      provider-name  "delprov"
                      product-name   "delprod"
                      repo-name      "delrepo"
                      repo-url       "http://blah.com/blah"]
          (try
            (create-org-with-provider-and-repo   org-name provider-name product-name repo-name repo-url)
            (organization/switch                          (@config :admin-org))
            (organization/delete                 org-name)
            ;;wait for delayed job to delete org
            (Thread/sleep                        30000)
            (create-org-with-provider-and-repo   org-name provider-name product-name repo-name repo-url)
            (finally
              (organization/switch                        (@config :admin-org)))))))))
