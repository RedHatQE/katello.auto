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
                     [conf :refer [config]]
                     [blockers :refer [bz-bugs]])
            [test.assert :as assert]
            [serializable.fn :refer [fn]]
            [slingshot.slingshot :refer [try+]]
            [test.tree.script :refer :all]
            [clojure.string :refer [capitalize upper-case lower-case]]
            [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]))

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
  (for [trailing-ws-str validation/trailing-whitespace-strings]
    [(mkorg trailing-ws-str) ::notification/name-no-leading-trailing-whitespace]))

(def name-taken-error (common/errtype ::notification/name-taken-error))
(def label-taken-error (common/errtype ::notification/label-taken-error))

;; Tests

(defgroup org-tests

  (deftest "Create an organization"
    :uuid "bc848668-db91-6104-7493-0ea333e53744"
    (create-and-verify-with-basename "auto-org")

    (deftest "Create an organization with valid name"
      :uuid "1abae899-440f-d254-15ab-81341908d0d2"
      :blockers (bz-bugs "975593")
      :data-driven true

      create-and-verify-with-basename
      (map list validation/non-html-names))

    (deftest "Create an org with a 1 character UTF-8 name"
      :uuid "b64a6748-c4c7-ef64-688b-59b85b4dcb55"
      :blockers (bz-bugs "975593")
      :data-driven true

      create-and-verify-with-name

      ;; create rows of data, 1 random 1-char utf8 string in each
      ;; use random because otherwise rerunning the test will fail
      ;; due to org already existing.
      (take 10 (repeatedly (comp vector
                                 (partial random-unicode-string 1)))))

    (deftest "Create an organization with an initial environment"
      :uuid "8bd2ed1a-409a-62d4-7f8b-a9394bb16890"
      :blockers (list rest/katello-only)
      (-> (kt/newOrganization {:name "auto-org"
                               :initial-env (kt/newEnvironment {:name "environment"})})
          uniqueify
          create-and-verify))
    
    (deftest "Create an organization with dot in name and query for provider"
      :uuid "443a9bfa-edc0-4164-2bdb-75fddb37f062"
      :blockers (list rest/katello-only)
      (with-unique [org (kt/newOrganization {:name "auto.org"})
                    provider (kt/newProvider {:name "custom_provider" :org org})]
        (ui/create-all (list org provider))
        (assert (rest/exists? provider))))
        
    (deftest "Two organizations with the same name is disallowed"
      :uuid "b3d2d598-e72d-6974-73e3-786059ab64cf"
      :blockers (bz-bugs "726724")

      (with-unique [org (kt/newOrganization {:name "test-dup"
                                             :description "org-description"})]
        (validation/expecting-error-2nd-try name-taken-error (ui/create org))))

    (deftest "Organization name is required when creating organization"
      :uuid "ea841120-469b-9f54-f1f3-d3345b731055"
      :blockers (bz-bugs "726724")

      (expecting-error validation/name-field-required
                       (ui/create (kt/newOrganization {:name ""
                                                       :description "org with empty name"}))))

    (deftest "Verify proper error message when invalid org name is used"
      :uuid "87546a36-b27f-2354-e88b-bc63e62585ce"
      :data-driven true
      :blockers (bz-bugs "726724")

      verify-bad-entity-create-gives-expected-error
      bad-org-names)

    (deftest "Verify HTML names are properly escaped"
      :uuid "ef423cc9-bbde-9b64-7adb-647287a96807"
      :blockers (bz-bugs "987909")
      :data-driven true
      (fn [f-str attr attr-val innertext]
        (let [n (format f-str attr attr-val (uniqueify innertext))
              notifs (-> n mkorg ui/create)
              escaped (fn [attr innertext msg]
                        (let [found-in #(.contains %2 %1)]
                          (= (found-in innertext msg)
                             (found-in attr msg))))]
          (assert/is (some #{n} (common/extract-left-pane-list)))
          ;; no notifs contain the innertext but not the attr
          ;; FIXME this doesn't properly capture how the app renders
          ;; text in the notif, currently doesn't escape it. getting
          ;; notif text from javascript though, shows it as escaped.
          (assert/is (every? (partial escaped attr innertext)
                             (mapcat :notices notifs)))))
      [["<a %1$s='%2$s'>%3$s</a>" "href" "http://foo.com/" "foo"]])
    

    (deftest "Edit an organization"
      :uuid "68b51c16-9596-7804-4ba3-ddd1e8eb1dd9"
      (with-unique [org (mkorg "auto-edit")]
        (ui/create org)
        (ui/update org assoc :description "edited description")))

    (deftest "Organization names and labels are unique to all orgs"
      :uuid "8813bfb2-b15b-64f4-f493-b73cd52a0e9a"
      (with-unique [org1 (kt/newOrganization {:name "myorg" :label "mylabel"})
                    org2 (kt/newOrganization {:name "yourorg" :label "yourlabel"})]
        (ui/create org1)
        (expecting-error name-taken-error
                         (ui/create (assoc org1 :label (:label org2))))
        (expecting-error label-taken-error
                         (ui/create (assoc org2 :label (:label org1))))))

    (deftest "Delete an organization"
      :uuid "54ebb248-5171-2c44-7523-b9038ec732e1"
      :blockers (bz-bugs "716972" "959485")

      (with-unique [org (mkorg "auto-del")]
        (ui/create org)
        (ui/delete org)
        (assert/is (rest/not-exists? org)))

      (deftest "Create an org with content, delete it and recreate it"
        :uuid "4488bfc4-e4a4-6454-42eb-c41666af18bd"
        :blockers (list rest/katello-only)


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
      :uuid "69e2e49d-2a13-2944-69b3-4f0bbdae42f8"
      :blockers (conj (bz-bugs "966670" "983994") rest/katello-only)
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
      :uuid "cc36e593-19b9-9384-b6b3-fe30f9e85590"
      :blockers (bz-bugs "919373" "951231" "951197")
      :data-driven true

      (fn [keyname success?]
        (with-unique [org (kt/newOrganization {:name "keyname-org"
                                               :label (uniqueify "org-label")
                                               :initial-env (kt/newEnvironment {:name "keyname-env", :label "env-label"})})]
          (ui/create org)
          (assert/is (not (organization/isKeynamePresent? keyname)))
          (organization/add-custom-keyname org ::organization/system-default-info-page keyname)
          (assert/is (= (organization/isKeynamePresent? keyname) success?))))

      [["Color" true]
       [(random-ascii-string 255) true]      
       [(random-unicode-string 10) true]      
       ["bar_+{}|\"?hi" true]
       ["bar_+{}|\"?<blink>hi</blink>" true]])

    (deftest "Create org with default keyname value twice"
      :uuid "7b451cdd-99e7-0d74-8a4b-605567b19b41"
      :blockers (bz-bugs "951197")
      (with-unique [org (kt/newOrganization {:name "keyname-org"
                                             :label (uniqueify "org-label")
                                             :initial-env (kt/newEnvironment {:name "keyname-env", :label "env-label"})})
                    keyname "default-keyname"]
        (ui/create org)
        (organization/add-custom-keyname org ::organization/system-default-info-page keyname)
        (assert/is (organization/isKeynamePresent? keyname))
        (expecting-error (common/errtype ::notification/already-contains-default-info) (organization/add-custom-keyname org ::organization/system-default-info-page keyname))))

    (deftest "Create org with default keyname and delete keyname"
      :uuid "68170be0-4c51-c224-8e53-58cb4820f061"
      (with-unique [org (kt/newOrganization {:name "keyname-org"
                                             :label (uniqueify "org-label")})
                    keyname "deleteme-keyname"]
        (ui/create org)
        (organization/add-custom-keyname org ::organization/system-default-info-page keyname)
        (assert/is (organization/isKeynamePresent? keyname))
        (organization/remove-custom-keyname org ::organization/system-default-info-page keyname)
        (assert/is (not (organization/isKeynamePresent? keyname)))))


    (deftest "Create org with default distributor keyname field"
      :uuid "51f0c00b-166e-bed4-2523-16a32825a11a"
      :blockers (bz-bugs "919373" "951231" "951197")
      :data-driven true

      (fn [keyname success?]
        (with-unique [org (kt/newOrganization {:name "keyname-org"
                                               :label (uniqueify "org-label")})]
          (ui/create org)
          (assert/is (not (organization/isKeynamePresent? keyname)))
          (organization/add-custom-keyname org ::organization/distributor-default-info-page keyname)
          (assert/is (= (organization/isKeynamePresent? keyname) success?))))

      [["Color" true]
       [(random-ascii-string 255) true]     
       [(random-unicode-string 10) true]     
       ["bar_+{}|\"?hi" true]
       ["bar_+{}|\"?<blink>hi</blink>" true]])
    
    (deftest "Org: Default distributor keyname char limit validation"
      :uuid "3301fae9-282e-4a05-8903-dad35a516e15"
      :data-driven true

      (fn [keyname expected-error]
        (with-unique [org (kt/newOrganization {:name "keyname-org"
                                               :label (uniqueify "org-label")})]
          (ui/create org)
          (expecting-error (common/errtype expected-error)
            (organization/add-custom-keyname org ::organization/distributor-default-info-page keyname))))
 
      [[(random-ascii-string 256) :katello.notifications/default-org-info-255-char-limit]
       [(random-unicode-string 256) :katello.notifications/default-org-info-255-char-limit]])
    
    (deftest "Org: Default System keyname char limit validation"
      :uuid "6a357b3f-515c-48d6-a2ea-57112e8e813e"
      :data-driven true
      
      (fn [keyname expected-error]
        (with-unique [org (kt/newOrganization {:name "keyname-org"
                                               :label (uniqueify "org-label")
                                               :initial-env (kt/newEnvironment {:name "keyname-env", :label "env-label"})})]
          (ui/create org)
          (expecting-error (common/errtype expected-error)
            (organization/add-custom-keyname org ::organization/system-default-info-page keyname))))
 
      [[(random-ascii-string 256) :katello.notifications/default-org-info-255-char-limit]
       [(random-unicode-string 256) :katello.notifications/default-org-info-255-char-limit]])

    (deftest "Create org with default distributor keyname and delete keyname"
      :uuid "80a04f72-4194-5c54-e1db-0f2e43ee0c67"
      (with-unique [org (kt/newOrganization {:name "keyname-org"
                                             :label (uniqueify "org-label")})
                    keyname "deleteme-keyname"]
        (ui/create org)
        (organization/add-custom-keyname org ::organization/distributor-default-info-page keyname)
        (assert/is (organization/isKeynamePresent? keyname))
        (organization/remove-custom-keyname org ::organization/distributor-default-info-page keyname)
        (assert/is (not (organization/isKeynamePresent? keyname)))))))


(defgroup org-tests11
  (deftest "Verify HTML names are properly escaped"
      :uuid "ef423cc9-bbde-9b64-7adb-647287a96807"
      :blockers (bz-bugs "987909")
      :data-driven true
      (fn [f-str attr attr-val innertext]
        (let [n (format f-str attr attr-val (uniqueify innertext))
              notifs (-> n mkorg ui/create)
              escaped (fn [attr innertext msg]
                        (let [found-in #(.contains %2 %1)]
                          (= (found-in innertext msg)
                             (found-in attr msg))))]
          (assert/is (some #{n} (common/extract-left-pane-list)))
          ;; no notifs contain the innertext but not the attr
          ;; FIXME this doesn't properly capture how the app renders
          ;; text in the notif, currently doesn't escape it. getting
          ;; notif text from javascript though, shows it as escaped.
          (assert/is (every? (partial escaped attr innertext)
                             (mapcat :notices notifs)))))
      [["<a %1$s='%2$s'>%3$s</a>" "href" "http://foo.com/" "foo"]]))

