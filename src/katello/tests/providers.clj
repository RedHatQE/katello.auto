(ns katello.tests.providers
  (:refer-clojure :exclude [fn])
  (:require [test.tree.script  :refer [deftest defgroup]]
            [serializable.fn   :refer [fn]]
            [test.assert       :as assert]
            [bugzilla.checker  :refer [open-bz-bugs]]
            (katello [rest :as rest]
                     [ui :as ui]
                     [tasks           :refer :all]
                     [ui-common       :as common]
                     [notifications   :refer [success?]]
                     [organizations   :as organization]
                     [environments   :as env]
                     [sync-management :as sync]
                     [repositories    :as repo]
                     [providers       :as provider]
                     [gpg-keys        :as gpg-key]
                     [fake-content    :as fake]
                     [validation      :refer :all]
                     [conf            :as conf :refer [config]])
            [katello.tests.useful :refer [fresh-repo create-series create-recursive]]))

;; Functions

(defn verify-provider-renamed
  "Verifies that a provider named old-prov doesn't exist, that that a
  provider named new-prov does exist."
  [old-prov new-prov]
  (assert (and (rest/exists? new-prov)
               (not (rest/exists? old-prov)))))

(defn validation
  "Attempts to create a provider and validates the result using
   pred."
  [provider pred]
  (expecting-error pred (ui/create (katello/newProvider
                                    (assoc provider :org conf/*session-org*)))))

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

(defn create-custom-provider-with-gpg-key
  "Creates a provider with products and repositories that use the provided gpg-key. returns the provider."
  [gpg-key]
  (with-unique [provider (katello/newProvider {:name "custom_provider" :org (:org gpg-key conf/*session-org*)})
                [product1 product2] (katello/newProduct {:name "fake1" :provider provider})
                repo1 (katello/newRepository {:name "testrepo1"
                                              :product product1
                                              :url (-> fake/custom-repos first :url)
                                              :gpg-key gpg-key})
                repo2 (katello/newRepository {:name "testrepo2"
                                              :product product2
                                              :url (-> fake/custom-repos second :url)
                                              :gpg-key gpg-key})]
    (if (rest/not-exists? gpg-key) (ui/create gpg-key))
    (ui/create-all (list provider product1 product2 repo1 repo2))
    provider))

;; Tests

(defgroup gpg-key-tests

  (deftest "Create a new GPG key from text input"
    :blockers rest/katello-only

    (-> {:name "test-key-text", :contents "asdfasdfasdfasdfasdfasdfasdf", :org conf/*session-org*}
        katello/newGPGKey
        uniqueify
        ui/create)

    (deftest "Create a new GPG key from text input and associate it with products/providers"
      :blockers rest/katello-only

      (-> {:name "test-key-text", :contents "asdfasdfasdfasdfasdfasdfasdf", :org conf/*session-org*}
          katello/newGPGKey
          uniqueify
          create-custom-provider-with-gpg-key)))

  (deftest "Create a new GPG key from file"
    :blockers (open-bz-bugs "835902" "846432")

    (-> {:name "test-key-file", :url (@config :gpg-key), :org conf/*session-org*}
        katello/newGPGKey
        uniqueify
        ui/create)

    (deftest "Create a new GPG key from file and associate it with products/providers"
      :blockers rest/katello-only

      (-> {:name "test-key-text", :url (@config :gpg-key), :org conf/*session-org*}
          katello/newGPGKey
          uniqueify
          create-custom-provider-with-gpg-key)

      (deftest "Associate same GPG key to multiple providers"
        :blockers rest/katello-only
        :tcms "https://tcms.engineering.redhat.com/case/202718/?from_plan=7759"

        (with-unique [test-org    (katello/newOrganization {:name "test-org" :initial-env-name "DEV"})
                      gpg-key     (katello/newGPGKey {:name "test-key" :url (@config :gpg-key) :org test-org})]
          (ui/create test-org)
          (create-custom-provider-with-gpg-key gpg-key)
          (create-custom-provider-with-gpg-key gpg-key))))
    
    (deftest "Delete existing GPG key"
      (doto (-> {:name (uniqueify "test-key"), :url (@config :gpg-key), :org conf/*session-org*}
                katello/newGPGKey)
        ui/create
        ui/delete)

      (deftest "Delete existing GPG key, associated with products/providers"
        :blockers rest/katello-only

        (doto (-> {:name "test-key", :url (@config :gpg-key), :org conf/*session-org*}
                  katello/newGPGKey
                  uniqueify)
          create-custom-provider-with-gpg-key
          ui/delete)))))


#_(defgroup package-filter-tests

  (deftest "Create new Package Filter test"
    (with-unique [test-package-filter "test-package-filter"]
      (filter/create test-package-filter {:description "Test filter"}))

    (deftest "Delete existing Package Filter test"
      (with-unique [test-package-filter "test-package-filter"]
        (filter/create test-package-filter {:description "Test filter"})
        (filter/remove test-package-filter)))))

(defgroup provider-tests

  (deftest "Create a custom provider"
    (-> {:name "auto-cp", :description "my description", :org conf/*session-org*}
        katello/newProvider
        uniqueify
        ui/create)

    (deftest "Cannot create two providers in the same org with the same name"
      (with-unique [provider (katello/newProvider {:name "dupe"
                                                   :org conf/*session-org*})]
        (expecting-error-2nd-try duplicate-disallowed
                                 (ui/create provider))))

    (deftest "Provider validation"
      :data-driven true
      :description "Creates a provider using invalid data, and
                    verifies that an error notification is shown in
                    the UI."
      validation
      (get-validation-data))

    (deftest "Rename a custom provider"
      (let [provider (-> {:name "rename", :description "my description", :org conf/*session-org*}
                         katello/newProvider
                         uniqueify)]
        (ui/create provider)
        (let [updated (ui/update provider update-in  [:name] str "-newname")]
          (verify-provider-renamed provider updated))))

    (deftest "Delete a custom provider"
      (doto (-> {:name "auto-provider-delete"
                 :org conf/*session-org*}
                katello/newProvider
                uniqueify)
        (ui/create)
        (ui/delete)))

    (deftest "Create two providers with the same name, in two different orgs"
      (with-unique [provider (katello/newProvider {:name "prov"})]
        (doseq [org (->> {:name "prov-org"}
                         katello/newOrganization
                         uniques
                         (take 2))]
          (create-recursive (assoc provider :org org))))))

  gpg-key-tests)
