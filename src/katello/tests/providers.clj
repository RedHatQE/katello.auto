(ns katello.tests.providers
  (:refer-clojure :exclude [fn])
  (:require [serializable.fn   :refer [fn]]
            [test.assert       :as assert]
            [test.tree.script :refer [defgroup deftest]]
            [katello :as kt]
            (katello [rest :as rest]
                     [ui :as ui]
                     [client :as client]
                     [tasks           :refer :all]
                     [ui-common       :as common]
                     [organizations   :as organization]
                     [environments   :as env]
                     [sync-management :as sync]
                     [repositories    :as repo]
                     [providers       :as provider]
                     [changesets :as changeset]
                     [systems :as system]
                     [gpg-keys        :as gpg-key]
                     [fake-content    :as fake]
                     [validation      :refer :all]
                     [conf            :as conf :refer [config]]
                     [blockers :refer [bz-bugs]])
            [katello.tests.useful :refer [fresh-repo create-series create-recursive]]
            [katello.client.provision :as provision]))

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
  (let [success? #(= (:type %) :success)]
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
         :url "http://sdf.com"} (common/errtype :katello.notifications/name-must-not-contain-characters)]))))

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

(defn create-test-environment []
  (def test-environment (first conf/*environments*))
  (create-recursive test-environment))

;; Tests

(defgroup gpg-key-tests
  :group-setup create-test-environment

  (deftest "Create a new GPG key from text input"
    :blockers (list rest/katello-only)

    (-> {:name "test-key-text", :contents "asdfasdfasdfasdfasdfasdfasdf", :org conf/*session-org*}
        katello/newGPGKey
        uniqueify
        ui/create)

    (deftest "Create a new GPG key from text input and associate it with products/providers"
      :blockers (list rest/katello-only)

      (-> {:name "test-key-text", :contents "asdfasdfasdfasdfasdfasdfasdf", :org conf/*session-org*}
          katello/newGPGKey
          uniqueify
          create-custom-provider-with-gpg-key)))

  (deftest "Create a new GPG key from file"
    :blockers (bz-bugs "835902" "846432")

    (-> {:name "test-key-file", :url (@config :gpg-key), :org conf/*session-org*}
        katello/newGPGKey
        uniqueify
        ui/create)

    (deftest "Create a new GPG key from file and associate it with products/providers"
      :blockers (list rest/katello-only)

      (-> {:name "test-key-text", :url (@config :gpg-key), :org conf/*session-org*}
          katello/newGPGKey
          uniqueify
          create-custom-provider-with-gpg-key)

      (deftest "Associate same GPG key to multiple providers"
        :blockers (list rest/katello-only)
        :tcms "https://tcms.engineering.redhat.com/case/202718/?from_plan=7759"

        (with-unique [test-org    (katello/newOrganization {:name "test-org" :initial-env (kt/newEnvironment {:name "DEV"})})
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
        :blockers (list rest/katello-only)

        (doto (-> {:name "test-key", :url (@config :gpg-key), :org conf/*session-org*}
                  katello/newGPGKey
                  uniqueify)
          create-custom-provider-with-gpg-key
          ui/delete)))
    
    (deftest  "Add key after product has been synced/promoted"
      :blockers (conj (bz-bugs "970570") rest/katello-only)
      (let [gpgkey (-> {:name "mykey", :org conf/*session-org*,
                        :contents (slurp "http://inecas.fedorapeople.org/fakerepos/zoo/RPM-GPG-KEY-dummy-packages-generator")}
                       kt/newGPGKey
                       uniqueify)
            repo1  (fresh-repo conf/*session-org* "http://inecas.fedorapeople.org/fakerepos/zoo/")
            repo2  (fresh-repo conf/*session-org* (-> fake/custom-provider first :products first :repos first :url))
            prd1   (kt/product repo1)
            prd2   (kt/product repo2)
            prv1   (kt/provider repo1)
            prv2   (kt/provider repo2)]
        (ui/create-all (list gpgkey prv1 prv2 prd1 prd2 repo1 repo2))
        (changeset/sync-and-promote (list repo1) test-environment)
        (provision/with-queued-client
          ssh-conn
          (client/register ssh-conn {:username (:name conf/*session-user*)
                                     :password (:password conf/*session-user*)
                                     :org (:name (kt/org repo1))
                                     :env (:name test-environment)
                                     :force true})
          (let [mysys              (-> {:name (client/my-hostname ssh-conn) :env test-environment}
                                       katello/newSystem)
                deletion-changeset (-> {:name "deletion-cs"
                                        :content (distinct (map :product (list repo1)))
                                        :env test-environment
                                        :deletion? true}
                                       katello/newChangeset
                                       uniqueify)
                promotion-changeset (-> {:name "re-promotion-cs"
                                         :content (distinct (map :product (list repo1)))
                                         :env test-environment}
                                        katello/newChangeset
                                        uniqueify)]
            (client/subscribe ssh-conn (system/pool-id mysys prd1))
            (client/sm-cmd ssh-conn :refresh)
            (client/run-cmd ssh-conn "yum repolist")
            (let [cmd (format "cat /etc/yum.repos.d/redhat.repo | grep -i \"gpgcheck = 0\"")
                  result (client/run-cmd ssh-conn cmd)]
              (assert/is (->> result :exit-code (= 0))))
            (client/sm-cmd ssh-conn :unsubscribe {:all true})
            (changeset/promote-delete-content deletion-changeset)
            (ui/update (kt/product repo1) assoc :gpg-key (:name gpgkey))
            (changeset/promote-delete-content promotion-changeset)
            (client/subscribe ssh-conn (system/pool-id mysys prd1))
            (client/sm-cmd ssh-conn :refresh)
            (client/run-cmd ssh-conn "rm -f /etc/yum.repos.d/redhat.repo")
            (client/run-cmd ssh-conn "yum repolist")
            (let [cmd (format "cat /etc/yum.repos.d/redhat.repo | grep -i \"gpgcheck = 1\"")
                  result (client/run-cmd ssh-conn cmd)]
              (assert/is (->> result :exit-code (= 0))))))))))


#_(defgroup package-filter-tests

    (deftest "Create new Package Filter test"
      :uuid "d547ee8a-e863-a4c4-4e83-8f7a5e21b142"
    (with-unique [test-package-filter "test-package-filter"]
      (filter/create test-package-filter {:description "Test filter"}))

    (deftest "Delete existing Package Filter test"
      :uuid "fab190c6-0f61-37a4-3ca3-7fe40a65d579"
      (with-unique [test-package-filter "test-package-filter"]
        (filter/create test-package-filter {:description "Test filter"})
        (filter/remove test-package-filter)))))

(defgroup provider-tests

  (deftest "Create a custom provider"
    :uuid "34a037ee-8530-9204-639b-06006c5b1dd6"
    (-> {:name "auto-cp", :description "my description", :org conf/*session-org*}
        katello/newProvider
        uniqueify
        ui/create)

    (deftest "Cannot create two providers in the same org with the same name"
      :uuid "b7edc321-6d63-6db4-20cb-6e254aca2a28"
      (with-unique [provider (katello/newProvider {:name "dupe"
                                                   :org conf/*session-org*})]
        (expecting-error-2nd-try duplicate-disallowed
                                 (ui/create provider))))

    (deftest "Provider validation"
      :uuid "3a0b3082-1091-3cc4-ade3-cc968a82f278"
      :data-driven true
      :description "Creates a provider using invalid data, and
                    verifies that an error notification is shown in
                    the UI."
      validation
      (get-validation-data))

    (deftest "Rename a custom provider"
      :uuid "45ad01c6-34ba-7074-cdbb-df3667a4a217"
      (let [provider (-> {:name "rename", :description "my description", :org conf/*session-org*}
                         katello/newProvider
                         uniqueify)]
        (ui/create provider)
        (let [updated (ui/update provider update-in  [:name] str "-newname")]
          (verify-provider-renamed provider updated))))

    (deftest "Delete a custom provider"
      :uuid "9ed6ab33-58cc-46f4-0483-c0f86f1b5712"
      (doto (-> {:name "auto-provider-delete"
                 :org conf/*session-org*}
                katello/newProvider
                uniqueify)
        (ui/create)
        (ui/delete)))

    (deftest "Create two providers with the same name, in two different orgs"
      :uuid "939fb331-058e-e5e4-ebbb-543da0e3cc30"
      (with-unique [provider (katello/newProvider {:name "prov"})]
        (doseq [org (->> {:name "prov-org"}
                         katello/newOrganization
                         uniques
                         (take 2))]
          (create-recursive (assoc provider :org org))))))

  gpg-key-tests)
