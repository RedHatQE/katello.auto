(ns katello.tests.activation-keys
  (:refer-clojure :exclude [fn])
  (:require (katello [api-tasks :as api]
                     [activation-keys :as ak]
                     [organizations :as org]
                     [client :as client]
                     [ui-common :as common]
                     [tasks :refer :all]
                     [systems :as system]
                     [validation :as val]
                     [fake-content  :as fake]
                     [conf :refer [*environments*]])
            [katello.client.provision :as provision]
            [katello.tests.systems :as system-tests]
            (test.tree [script :refer [defgroup deftest]]
                       [builder :refer [union]])
            [serializable.fn :refer [fn]]
            [test.assert :as assert]
            [bugzilla.checker :refer [open-bz-bugs]]))

;; Tests

(defgroup ak-tests
  :group-setup #(system-tests/create-test-environment)
  :test-setup org/before-test-switch
  
  (deftest "Create an activation key"
    :blockers (open-bz-bugs "750354")

    (ak/create {:name (uniqueify "auto-key")
                :description "my description"
                :environment system-tests/test-environment})

    (deftest "Create an activation key with i18n characters"
      :data-driven true
      (fn [name]
        (with-unique [ak-name name]
          (ak/create {:name ak-name
                      :description "my description"
                      :environment system-tests/test-environment} )))
      val/i8n-chars)

    (deftest "Remove an activation key"
      (with-unique [ak-name "auto-key-deleteme"]
        (ak/create {:name ak-name
                    :description "my description"
                    :environment system-tests/test-environment} )
        (ak/delete ak-name)))


    (deftest "activation-key-dupe-disallowed"
      (with-unique [ak-name "auto-key"]
        (val/expecting-error-2nd-try val/duplicate-disallowed
                                     (ak/create
                                      {:name ak-name
                                       :description "my description"
                                       :environment system-tests/test-environment}))))

    (deftest "create activation keys with subscriptions"
      (with-unique [ak-name "act-key"
                    test-org1 "redhat-org"]
        (do
          (let [envz (take 3 (unique-names "env"))]
            (fake/setup-org test-org1 envz)
            (org/switch test-org1)
            (ak/create {:name ak-name
                        :description "my act keys"
                        :environment (first envz)})
            (ak/add-subscriptions ak-name fake/subscription-names)
            (assert/is (some #{(first fake/subscription-names)}
                             (ak/get-subscriptions ak-name))))))))

  (deftest "Delete activation key after registering a system with it"
    (with-unique [system-name "mysystem"
                  key-name "auto-key"]
      (let [target-env (first *environments*)]
        (api/ensure-env-exist target-env {:prior library})
        (ak/create {:name key-name
                    :description "my description"
                    :environment target-env})
        (provision/with-client "ak-delete" ssh-conn
          (client/register ssh-conn
                           {:org "ACME_Corporation"
                            :activationkey key-name})
          (ak/delete key-name)
          (client/sm-cmd ssh-conn :refresh))))))
