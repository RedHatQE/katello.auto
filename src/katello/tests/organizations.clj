(ns katello.tests.organizations
  (:refer-clojure :exclude [fn])
  (:require [katello :as kt]
            (katello [ui-common :as common]
                     [ui :as ui]
                     [rest :as rest]
                     [validation :as validation]
                     [repositories :as repo]
                     [tasks :refer :all]
                     [notifications :as notification]
                     [organizations :as organization]
                     [environments :as environment]
                     [changesets :as changeset]
                     [fake-content :as fake]
                     [conf :refer [config]]) 
            [test.assert :as assert]
            [serializable.fn :refer [fn]]
            [slingshot.slingshot :refer [try+]]
            [test.tree.script :refer :all]
            [clojure.string :refer [capitalize upper-case lower-case]]
            [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            [bugzilla.checker :refer [open-bz-bugs]]))

;; Functions

(defn verify-bad-entity-create-gives-expected-error
  [ent expected-error]
  (expecting-error (common/errtype expected-error) (ui/create ent)))

(defn mkorg [name]
  (kt/newOrganization {:name name}))

(defn create-and-verify [org]
  (ui/create org)
  (assert/is (rest/exists? org)))

(def create-and-verify-with-name
  (comp create-and-verify mkorg))

(def create-and-verify-with-basename
  (comp create-and-verify uniqueify mkorg))

(defn setup-custom-org-with-content
  [env repos]
  (ui/create-all-recursive (concat (list (kt/org env) env) repos))
  (changeset/sync-and-promote (filter (complement :unsyncable) repos) env))


;; Data (Generated)

(def bad-org-names
  (for [[name err] (concat
                    (for [inv-char-str validation/invalid-character-strings]
                      [inv-char-str ::notification/org-name-must-not-contain-html])
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
      (-> (kt/newOrganization {:name "auto-org"
                               :initial-env (kt/newEnvironment {:name "environment"})})
          uniqueify
          create-and-verify))

    (deftest "Two organizations with the same name is disallowed"
      :blockers (open-bz-bugs "726724")

      (with-unique [org (kt/newOrganization {:name "test-dup"
                                             :description "org-description"})]
        (validation/expecting-error-2nd-try name-taken-error (ui/create org))))

    (deftest "Organization name is required when creating organization"
      :blockers (open-bz-bugs "726724")

      (expecting-error validation/name-field-required
                       (ui/create (kt/newOrganization {:name ""
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
      (with-unique [org1 (kt/newOrganization {:name "myorg" :label "mylabel"})
                    org2 (kt/newOrganization {:name "yourorg" :label "yourlabel"})]
        (ui/create org1)
        (expecting-error name-taken-error
                         (ui/create (assoc org1 :label (:label org2))))
        (expecting-error label-taken-error
                         (ui/create (assoc org2 :label (:label org1))))))

    (deftest "Delete an organization"
      :blockers (open-bz-bugs "716972" "959485")

      (with-unique [org (mkorg "auto-del")]
        (ui/create org)
        (ui/delete org)
        (assert/is (rest/not-exists? org)))

      (deftest "Create an org with content, delete it and recreate it"
        :blockers rest/katello-only


        (with-unique [org (mkorg "delorg")
                      env (kt/newEnvironment {:name "env" :org org})]
          (let [repos (for [r fake/custom-repos]
                        (update-in r [:product :provider] assoc :org org))]
           (setup-custom-org-with-content env repos)
           ;; not allowed to delete the current org, so switch first.
           (organization/switch)
           (ui/delete org)
           (setup-custom-org-with-content env repos)))))

    (deftest "Creating org with default env named or labeled 'Library' is disallowed"
      :data-driven true

      (fn [env-name env-lbl notif]
        (with-unique [org (kt/newOrganization
                           {:name "lib-org", :initial-env (kt/newEnvironment {:name env-name, :label env-lbl})})]
          (expecting-error (common/errtype notif)
                           (ui/create org))))

      [["Library" "Library" ::notification/env-name-lib-is-builtin]
       ["Library" "Library" ::notification/env-label-lib-is-builtin]
       ["Library" (with-unique [env-lbl "env-label"] env-lbl) ::notification/env-name-lib-is-builtin]
       [(with-unique [env-name "env-name"] env-name) "Library" ::notification/env-label-lib-is-builtin]])

    (deftest "Create org with default system keyname field"
      :blockers (open-bz-bugs "919373" "951231" "951197")
      :data-driven true

      (fn [keyname success?]
        (with-unique [org (kt/newOrganization {:name "keyname-org"
                                             :label (uniqueify "org-label")
                                             :initial-env (kt/newEnvironment {:name "keyname-env", :label "env-label"})})]
          (ui/create org)
          (assert/is (not (organization/isKeynamePresent? keyname)))
          (organization/add-custom-keyname org ::organization/system-default-info-page keyname)
          (assert/is (organization/isKeynamePresent? keyname))))

      [["Color" true]
       [(random-string (int \a) (int \z) 255) true]
       [(random-string (int \a) (int \z) 256) false]
       [(random-string 0x0080 0x5363 10) true]
       [(random-string 0x0080 0x5363 256) false]
       ["bar_+{}|\"?hi" true]
       ["bar_+{}|\"?<blink>hi</blink>" false]])

    (deftest "Create org with default keyname value twice"
      :blockers (open-bz-bugs "951197")
      (with-unique [org (kt/newOrganization {:name "keyname-org"
                                             :label (uniqueify "org-label")
                                             :initial-env (kt/newEnvironment {:name "keyname-env", :label "env-label"})})
                    keyname "default-keyname"]
        (ui/create org)
        (organization/add-custom-keyname org ::organization/system-default-info-page keyname)
        (assert/is (organization/isKeynamePresent? keyname))
        (organization/add-custom-keyname org ::organization/system-default-info-page keyname)))

    (deftest "Create org with default keyname and delete keyname"
      (with-unique [org (kt/newOrganization {:name "keyname-org"
                                             :label (uniqueify "org-label")
                                             :initial-env (kt/newEnvironment {:name "keyname-env", :label "env-label"})})
                    keyname "deleteme-keyname"]
        (ui/create org)
        (organization/add-custom-keyname org ::organization/system-default-info-page keyname)
        (assert/is (organization/isKeynamePresent? keyname))
        (organization/remove-custom-keyname org ::organization/system-default-info-page keyname)
        (assert/is (not (organization/isKeynamePresent? keyname)))))


      (deftest "Create org with default distributor keyname field"
      :blockers (open-bz-bugs "919373" "951231" "951197")
      :data-driven true

      (fn [keyname success?]
        (with-unique [org (kt/newOrganization {:name "keyname-org"
                                             :label (uniqueify "org-label")
                                             :initial-env (kt/newEnvironment {:name "keyname-env", :label "env-label"})})]
          (ui/create org)
          (assert/is (not (organization/isKeynamePresent? keyname)))
          (organization/add-custom-keyname org ::organization/distributor-default-info-page keyname)
          (assert/is (organization/isKeynamePresent? keyname))))

      [["Color" true]
       [(random-string (int \a) (int \z) 255) true]
       [(random-string (int \a) (int \z) 256) false]
       [(random-string 0x0080 0x5363 10) true]
       [(random-string 0x0080 0x5363 256) false]
       ["bar_+{}|\"?hi" true]
       ["bar_+{}|\"?<blink>hi</blink>" false]])


      (deftest "Create org with default distributor keyname and delete keyname"
      (with-unique [org (kt/newOrganization {:name "keyname-org"
                                             :label (uniqueify "org-label")
                                             :initial-env (kt/newEnvironment {:name "keyname-env", :label "env-label"})})
                    keyname "deleteme-keyname"]
        (ui/create org)
        (organization/add-custom-keyname org ::organization/distributor-default-info-page keyname)
        (assert/is (organization/isKeynamePresent? keyname))
        (organization/remove-custom-keyname org ::organization/distributor-default-info-page keyname)
        (assert/is (not (organization/isKeynamePresent? keyname)))))))
