(ns katello.tests.organizations
  (:refer-clojure :exclude [fn])
  (:require [katello :refer [newOrganization newProvider newProduct newRepository]]
            (katello [ui-common :as common]
                     [ui :as ui]
                     [rest :as rest]
                     [validation :as validation] 
                     [repositories :as repo]
                     [tasks :refer :all] 
                     [notifications :as notification]
<<<<<<< HEAD
                     [organizations :as organization])
=======
                     [organizations :as organization] 
                     [environments :as environment]
                     [changesets :as changesets]
                     [fake-content :as fake]
                     [conf :refer [config]])
>>>>>>> master
            [test.assert :as assert]
            [serializable.fn :refer [fn]]
            [slingshot.slingshot :refer [try+]]
            [test.tree.script :refer :all]
            [clojure.string :refer [capitalize upper-case lower-case]]
            [bugzilla.checker :refer [open-bz-bugs]]))

;; Functions

(defn verify-bad-entity-create-gives-expected-error
  [ent expected-error]
  (expecting-error (common/errtype expected-error) (ui/create ent)))

(defn mkorg [name]
  (newOrganization {:name name}))

(defn create-and-verify [org]
  (ui/create org)
  (assert/is (rest/exists? org)))

(def create-and-verify-with-name
  (comp create-and-verify mkorg))

(def create-and-verify-with-basename
  (comp create-and-verify uniqueify mkorg))

(defn setup-custom-org-with-content
  [org-name envz promotion-content]
  (organization/create org-name)           
  (organization/switch org-name)
  (environment/create-path org-name envz)
  (fake/prepare-org-custom-provider org-name fake/custom-provider)
  (changesets/promote-delete-content library (first envz) false promotion-content))


;; Data (Generated)

(def bad-org-names
<<<<<<< HEAD
  (for [[name err] (concat
                    (for [inv-char-str validation/invalid-character-strings]
                      [inv-char-str ::notification/name-must-not-contain-characters])
                    (for [trailing-ws-str validation/trailing-whitespace-strings]
                      [trailing-ws-str ::notification/name-no-leading-trailing-whitespace]))]
    [(mkorg name) err]))
=======
  (concat
   (for [inv-char-str validation/invalid-org-name-strings]
     [inv-char-str ::notification/org-name-must-not-contain-html])
   (for [trailing-ws-str validation/trailing-whitespace-strings]
     [trailing-ws-str ::notification/name-no-leading-trailing-whitespace])))
>>>>>>> master

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
                         (ui/create (assoc org1 :label {:label org2})))
        (expecting-error label-taken-error
                         (ui/create (assoc org2 :label {:label org1})))))
    
    (deftest "Delete an organization"
      :blockers (open-bz-bugs "716972")
    
<<<<<<< HEAD
      (with-unique [org (mkorg "auto-del")]
        (ui/create org)
        (ui/delete org)
        (assert/is (rest/not-exists? org)))

=======
      (let [org-name (uniqueify "del-org")
              envz     (take 3 (unique-names "env"))
              promotion-content {:products (map :name (-> fake/custom-provider first :products))}]
          
          (setup-custom-org-with-content org-name envz promotion-content)
          (organization/switch (@config :admin-org))
          (organization/delete org-name)
          (assert/is (org-does-not-exist? org-name)))
    
>>>>>>> master
      (deftest "Create an org with content, delete it and recreate it"
        :blockers rest/katello-only
        
<<<<<<< HEAD
        (with-unique [org (mkorg "delorg")
                      provider (newProvider {:name "delprov" :org org})
                      product (newProduct {:name "delprod" :provider provider})
                      repo (newRepository {:name "delrepo" :product product
                                           :url "http://blah.com/blah"})]
          (let [create-all #(ui/create-all (list org provider product repo))]
            (create-all)
            ;; not allowed to delete the current org, so switch first.
            (organization/switch)
            (organization/delete org)
            (create-all)))))
=======
        (let [org-name (uniqueify "recreate-org")
              envz     (take 3 (unique-names "env"))
              promotion-content {:products (map :name (-> fake/custom-provider first :products))}]
          
          (setup-custom-org-with-content org-name envz promotion-content)
          (organization/switch (@config :admin-org))
          (organization/delete org-name)
          (setup-custom-org-with-content org-name envz promotion-content)
          (assert/is (org-exists? org-name)))))
>>>>>>> master
    
    (deftest "Creating org with default env named or labeled 'Library' is disallowed"
      :data-driven true

      (fn [env-name env-lbl notif]
        (with-unique [org (newOrganization {:name "lib-org"
                                            :initial-env-name env-name
                                            :initial-env-label env-lbl})]
          (expecting-error 
            (common/errtype notif)
            (ui/create org))))

<<<<<<< HEAD
      [["Library" "Library" ::notification/env-name-lib-is-builtin]
       ["Library" "Library" ::notification/env-label-lib-is-builtin]
       ["Library" (uniqueify "env-label") ::notification/env-name-lib-is-builtin]
       [(uniqueify "env-name") "Library" ::notification/env-label-lib-is-builtin]])))
=======
      [["lib-org" "Library" "Library" ::notification/env-name-lib-is-builtin]
       ["lib-org" "Library" "Library" ::notification/env-label-lib-is-builtin]
       ["lib-org" "Library" (with-unique [env-lbl "env-label"] env-lbl) ::notification/env-name-lib-is-builtin]
       ["lib-org" (with-unique [env-name "env-name"] env-name) "Library" ::notification/env-label-lib-is-builtin]])))
 
>>>>>>> master
