(ns katello.tests.organizations
  (:refer-clojure :exclude [fn])
  (:require [katello :refer [newOrganization]]
            (katello [ui-common :as common]
                     [ui :as ui]
                     [api-tasks :as api]
                     [validation :as validation] 
                     [providers :as provider]
                     [repositories :as repo]
                     [tasks :refer :all] 
                     [notifications :as notification]
                     [organizations :as organization] 
                     [conf :refer [config]])
            [test.assert :as assert]
            [serializable.fn :refer [fn]]
            [test.tree.script :refer :all]
            [clojure.string :refer [capitalize upper-case lower-case]]
            [bugzilla.checker :refer [open-bz-bugs]]))

;; Functions

(defn get-all-org-names
  "Returns a list of the names of all the organizations in the system.
   Uses the API."
  []
  (doall (map :name (api/all-entities :organization))))

(defn exists? [org]
  (some #{(:name org)} (get-all-org-names)))

(def does-not-exist? (complement exists?))

(defn verify-bad-entity-create-gives-expected-error
  [ent expected-error]
  (expecting-error (common/errtype expected-error) (ui/create ent)))

(defn create-org-with-provider-and-repo [org provider-name product-name repo-name repo-url]
  (ui/create org)
  (organization/switch org)
  (provider/create {:name provider-name
                    :description "provider to del and recreate"})
  (provider/add-product {:provider-name provider-name
                         :name product-name})
  (repo/add {:name repo-name
             :provider-name provider-name
             :product-name product-name
             :url repo-url}))

(defn mkorg [name]
  (newOrganization {:name name}))

(defn create-and-verify [org]
  (ui/create org)
  (assert/is (exists? org)))

(def create-and-verify-with-name
  (comp create-and-verify mkorg))

(def create-and-verify-with-basename
  (comp create-and-verify uniqueify mkorg))

;; Data (Generated)

(def bad-org-names
  (for [[name err] (concat
                    (for [inv-char-str validation/invalid-character-strings]
                      [inv-char-str ::notification/name-must-not-contain-characters])
                    (for [trailing-ws-str validation/trailing-whitespace-strings]
                      [trailing-ws-str ::notification/name-no-leading-trailing-whitespace]))]
    [(mkorg name) err]))

(def name-taken-error (common/errtype ::notification/name-taken-error))
(def label-taken-error (common/errtype ::notification/label-taken-error))

;; Tests

 (defgroup org-tests

   (deftest "Create an organization"
     (create-and-verify-with-basename "auto-org")
     
    (deftest "Create an organization with i18n characters"
      :data-driven true
      
      create-and-verify-with-basename
      validation/i8n-chars)

    (deftest "Create an org with a 1 character UTF-8 name"
      :data-driven true

      create-and-verify-with-name

      ;;create 5 rows of data, 1 random 1-char utf8 string in each
      (take 5 (repeatedly (comp vector
                                (partial random-string 0x0080 0x5363 1)))))
    
    (deftest "Create an organization with an initial environment"
      (-> (newOrganization {:name "auto-org"
                            :initial-env-name "environment"})
          uniqueify
          create-and-verify))
  
    (deftest "Two organizations with the same name is disallowed"
      :blockers (open-bz-bugs "726724")
      
      (with-unique [org (newOrganization {:name "test-dup"
                                          :description "org-description"})]
       (validation/expecting-error-2nd-try name-taken-error (ui/create org))))
  
    (deftest "Organization name is required when creating organization"
      :blockers (open-bz-bugs "726724")
      
      (expecting-error validation/name-field-required
                       (ui/create (newOrganization {:name ""
                                                    :description "org with empty name"}))))

    (deftest "Verify proper error message when invalid org name is used"
      :data-driven true
      :blockers (open-bz-bugs "726724")
      
      verify-bad-entity-create-gives-expected-error
      bad-org-names)

  
    (deftest "Edit an organization"
      (with-unique [org (mkorg "auto-edit")]
        (ui/create org)
        (ui/update org assoc :description "edited description")))

    (deftest "Organization names and labels are unique to all orgs"
      (with-unique [org1 (newOrganization {:name "myorg" :label "mylabel"})
                    org2 (newOrganization {:name "yourorg" :label "yourlabel"})]
        (ui/create org1)
        (expecting-error name-taken-error
                         (ui/create (assoc org1 {:label org2})))
        (expecting-error label-taken-error
                         (ui/create (assoc org2 {:label org1})))))
    
    (deftest "Delete an organization"
      :blockers (open-bz-bugs "716972")
    
      (with-unique [org (mkorg "auto-del")]
        (ui/create org)
        (ui/delete org)
        (assert/is (does-not-exist? org)))

      (deftest "Create an org with content, delete it and recreate it"
        :blockers api/katello-only
        
        (with-unique [org-name "delorg"
                      provider-name "delprov"
                      product-name "delprod"
                      repo-name "delrepo"
                      repo-url "http://blah.com/blah"]
          (try
            (create-org-with-provider-and-repo org-name provider-name product-name repo-name repo-url)
            (organization/switch (@config :admin-org))
            (organization/delete org-name)
            ;;wait for delayed job to delete org
            (Thread/sleep 30000)
            (create-org-with-provider-and-repo org-name provider-name product-name repo-name repo-url)
            (finally
              (organization/switch (@config :admin-org)))))))
    
    (deftest "Creating org with default env named or labeled 'Library' is disallowed"
      :data-driven true

      (fn [env-name env-lbl notif]
        (with-unique [org (newOrganization {:name "lib-org"
                                            :initial-env-name env-name
                                            :initial-env-label env-lbl})]
          (expecting-error 
            (common/errtype notif)
            (ui/create org))))

      [["Library" "Library" ::notification/env-name-lib-is-builtin]
       ["Library" "Library" ::notification/env-label-lib-is-builtin]
       ["Library" (with-unique [env-lbl "env-label"] env-lbl) ::notification/env-name-lib-is-builtin]
       [(with-unique [env-name "env-name"] env-name) "Library" ::notification/env-label-lib-is-builtin]])))
