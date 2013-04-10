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

#_(defn setup-custom-providers-with-gpg-keys [gpg-key-name url]
  (let [provider-name (uniqueify "custom_provider")
        product-name (uniqueify "custom_product")
        repo-name (uniqueify "zoo_repo")
        product-name2 (uniqueify "custom_product2")
        repo-name2 (uniqueify "zoo_repo2")]
    (provider/create {:name provider-name})
    (provider/add-product {:provider-name provider-name
                           :name product-name})
    (repo/add-with-key {:provider-name provider-name
                        :product-name product-name
                        :name repo-name
                        :url url
                        :gpgkey gpg-key-name})
    (provider/add-product {:provider-name provider-name
                           :name product-name2})
    (repo/add-with-key {:provider-name provider-name
                        :product-name product-name2
                        :name repo-name2
                        :url url
                        :gpgkey gpg-key-name})
    (assert/is (every? true? (for [reponame [repo-name repo-name2]]
                               (gpg-key/gpg-keys-prd-association? gpg-key-name reponame))))))


#_(defn create-gpg-key-with-products [gpg-key-type gpg-key gpg-key-name]
  ;; gpg-key is a string when type is content and
  ;; gpg-key is a url when type is url
  (let [org   (@config :admin-org)
        zoo-url    (-> fake/custom-providers first :products first :repos second :url)
        safari-url (-> fake/custom-provider first :products first :repos first :url)]
    (organization/switch org-name)
    (api/ensure-env-exist (first (@config :environments)) {:prior library})
    (gpg-key/create gpg-key-name {(keyword gpg-key-type) gpg-key})
    (setup-custom-providers-with-gpg-keys gpg-key-name zoo-url)
    (setup-custom-providers-with-gpg-keys gpg-key-name safari-url)))

#_(defn upload-gpg-key-to-multiple-orgs [gpg-key gpg-key-name]
  (let [test-org (uniqueify "custom-org")
        org-name (@config :admin-org)
        url      (-> fake/custom-providers first :products first :repos second :url)
        envz     (take 3 (unique-names "env3"))]
    (organization/switch org-name)
    (api/ensure-env-exist (first (@config :environments)) {:prior library})
    (gpg-key/create gpg-key-name {:contents gpg-key})
    (setup-custom-providers-with-gpg-keys gpg-key-name url)
    (organization/create test-org)
    (organization/switch test-org)
    (env/create-path test-org envz)
    (gpg-key/create gpg-key-name {:contents gpg-key})
    (setup-custom-providers-with-gpg-keys gpg-key-name url)
    (organization/switch org-name)))

;; Tests

(defgroup gpg-key-tests

  (deftest "Create a new GPG key from text input"
    :blockers rest/katello-only

    (-> {:name (uniqueify "test-key-text"), :contents "asdfasdfasdfasdfasdfasdfasdf", :org conf/*session-org*}
        katello/newGPGKey
        ui/create))

  (deftest "Create a new GPG key from file"
    :blockers (open-bz-bugs "835902" "846432")

    (-> {:name "test-key-file", :url (@config :gpg-key), :org conf/*session-org*}
        katello/newGPGKey
        uniqueify
        ui/create))

  (deftest "Delete existing GPG key"
      (doto (-> {:name "test-key", :url (@config :gpg-key), :org conf/*session-org*}
                katello/newGPGKey
                uniqueify)
        ui/create
        ui/delete))

  #_(deftest "Create a new GPG key from text input and associate it with products/providers"
    :blockers rest/katello-only

    (let  [gpg-key (slurp (@config :gpg-key))
           gpg-key-name (uniqueify "test-key-text2")]
      (create-gpg-key-with-products "contents" gpg-key gpg-key-name)))

  #_(deftest "Create a new GPG key from file and associate it with products/providers"
    :blockers rest/katello-only

    (let  [gpg-key-name (uniqueify "test-key-file2")
           gpg-key-url  (@config :gpg-key)]
      (create-gpg-key-with-products "url" gpg-key-url gpg-key-name)))

  #_(deftest "Delete existing GPG key, associated with products/providers"
    :blockers rest/katello-only

    (let [gpg-key      (slurp (@config :gpg-key))
          gpg-key-name (uniqueify "test-key-del")]
      (create-gpg-key-with-products "contents" gpg-key gpg-key-name)
      (gpg-key/remove gpg-key-name)))

  #_(deftest "Associate same GPG key to multiple orgs"
    :blockers rest/katello-only

    (let [gpg-key      (slurp (@config :gpg-key))
          gpg-key-name (uniqueify "test-key-multiorg")]
      (upload-gpg-key-to-multiple-orgs gpg-key gpg-key-name))))


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
        (let [updated (ui/update provider assoc :name "newname")]
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
