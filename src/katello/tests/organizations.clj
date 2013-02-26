(ns katello.tests.organizations
  (:refer-clojure :exclude [fn])
  (:require (katello [ui-common :as common]
                     [api-tasks :as api]
                     [validation :as validation] 
                     [providers :as provider]
                     [repositories :as repo]
                     [tasks :refer :all] 
                     [notifications :as notification]
                     [organizations :as organization] 
                     [environments :as environment]
                     [changesets :as changesets]
                     [fake-content :as fake]
                     [conf :refer [config]])
            [test.assert :as assert]
            [serializable.fn :refer [fn]]
            [test.tree.script :refer :all]
            [clojure.string :refer [capitalize upper-case lower-case]]
            [bugzilla.checker :refer [open-bz-bugs]]))

;; Functions

(defn create-test-org
  "Creates an organization named org-name via the API"
  [org-name]
  (api/create-organization org-name
                           {:description "organization used to test environments."}))

(defn get-all-org-names
  "Returns a list of the names of all the organizations in the system.
   Uses the API."
  []
  (doall (map :name (api/all-entities :organization))))

(defn org-exists? [org-name]
  (some #{org-name} (get-all-org-names)))

(def org-does-not-exist? (complement org-exists?))

(defn verify-bad-org-name-gives-expected-error
  [name expected-error]
  (expecting-error (common/errtype expected-error) (organization/create name)))

(defn create-org-with-provider-and-repo [org-name provider-name product-name repo-name repo-url]
  (organization/create org-name {:description "org to delete and recreate"})
  (organization/switch org-name)
  (provider/create {:name provider-name
                     :description "provider to del and recreate"})
  (provider/add-product {:provider-name provider-name
                          :name product-name})
  (repo/add {:name repo-name
                       :provider-name provider-name
                       :product-name product-name
                       :url repo-url}))

(defn setup-custom-org-with-content
  [org-name envz promotion-content]
  (organization/create org-name)           
  (organization/switch org-name)
  (environment/create-path org-name envz)
  (fake/prepare-org-custom-provider org-name fake/custom-provider)
  (changesets/promote-delete-content library (first envz) false promotion-content))


;; Data (Generated)

(def bad-org-names
  (concat
   (for [inv-char-str validation/invalid-character-strings]
     [inv-char-str ::notification/name-must-not-contain-characters])
   (for [trailing-ws-str validation/trailing-whitespace-strings]
     [trailing-ws-str ::notification/name-no-leading-trailing-whitespace])))

(def name-taken-error (common/errtype ::notification/name-taken-error))
(def label-taken-error (common/errtype ::notification/label-taken-error))

;; Tests

 (defgroup org-tests

   (deftest "Create an organization"
    (with-unique [org-name "auto-org"]
      (organization/create org-name)
      (assert/is (org-exists? org-name)))
    
    (deftest "Create an organization with i18n characters"
      :data-driven true
      
      (fn [org]
        (with-unique [org-name org]
          (organization/create org-name)
          (assert/is (org-exists? org-name))))
      
      validation/i8n-chars)

    (deftest "Create an org with a 1 character UTF-8 name"
      :data-driven true

      (fn [org-name]
        (organization/create org-name)
        (assert/is (org-exists? org-name)))

      ;;create 5 rows of data, 1 random 1-char utf8 string in each
      (take 5 (repeatedly (comp vector
                                (partial random-string 0x0080 0x5363 1)))))
    
    (deftest "Create an organization with an initial environment"
      (with-unique [org-name "auto-org"
                    env-name "environment"]
        (organization/create org-name {:initial-env-name env-name})
        (assert/is (org-exists? org-name))))
  
    (deftest "Two organizations with the same name is disallowed"
      :blockers (open-bz-bugs "726724")
      
      (with-unique [org-name "test-dup"]
        (validation/expecting-error-2nd-try name-taken-error
          (organization/create org-name {:description "org-description"}))))
  
    (deftest "Organization name is required when creating organization"
      :blockers (open-bz-bugs "726724")
      
      (expecting-error validation/name-field-required
                       (organization/create "" {:description "org with empty name"})))

    
    (deftest "Verify proper error message when invalid org name is used"
      :data-driven true
      :blockers (open-bz-bugs "726724")
      
      verify-bad-org-name-gives-expected-error
      bad-org-names)

  
    (deftest "Edit an organization"
      (with-unique [org-name "auto-edit"]
        (create-test-org org-name)
        (organization/edit org-name :description "edited description")))

    (deftest "Organization names and labels are unique to all orgs"
      (with-unique [name1 "name-1"
                    name2 "name-2"
                    label1 "label-1"
                    label2 "label-2"]
        (organization/create name1 {:label label1})
        (expecting-error name-taken-error
                         (organization/create name1 {:label label2}))
        (expecting-error label-taken-error
                         (organization/create name2 {:label label1}))))
    
    (deftest "Delete an organization"
      :blockers (open-bz-bugs "716972")
    
      (let [org-name (uniqueify "del-org")
              envz     (take 3 (unique-names "env"))
              promotion-content {:products (map :name (-> fake/custom-provider first :products))}]
          
          (setup-custom-org-with-content org-name envz promotion-content)
          (organization/switch (@config :admin-org))
          (organization/delete org-name)
          (assert/is (org-does-not-exist? org-name)))
    
      (deftest "Create an org with content, delete it and recreate it"
        :blockers api/katello-only
        
        (let [org-name (uniqueify "recreate-org")
              envz     (take 3 (unique-names "env"))
              promotion-content {:products (map :name (-> fake/custom-provider first :products))}]
          
          (setup-custom-org-with-content org-name envz promotion-content)
          (organization/switch (@config :admin-org))
          (organization/delete org-name)
          (setup-custom-org-with-content org-name envz promotion-content)
          (assert/is (org-exists? org-name)))))
    
    (deftest "Creating org with default env named or labeled 'Library' is disallowed"
      :data-driven true

      (fn [org-name-prefix env-name env-lbl notif]
        (with-unique [org-name org-name-prefix]
          (expecting-error 
            (common/errtype notif)
            (organization/create org-name {:initial-env-name env-name
                                           :initial-env-label env-lbl}))))

      [["lib-org" "Library" "Library" ::notification/env-name-lib-is-builtin]
       ["lib-org" "Library" "Library" ::notification/env-label-lib-is-builtin]
       ["lib-org" "Library" (with-unique [env-lbl "env-label"] env-lbl) ::notification/env-name-lib-is-builtin]
       ["lib-org" (with-unique [env-name "env-name"] env-name) "Library" ::notification/env-label-lib-is-builtin]])))
 
 (defgroup del-with-content
   
    (deftest "Delete an organization"
      :blockers (open-bz-bugs "716972")
    
      (let [org-name (uniqueify "del-org")
              envz     (take 3 (unique-names "env"))
              promotion-content {:products (map :name (-> fake/custom-provider first :products))}]
          
          (setup-custom-org-with-content org-name envz promotion-content)
          (organization/switch (@config :admin-org))
          (organization/delete org-name)
          (assert/is (org-does-not-exist? org-name)))))
 