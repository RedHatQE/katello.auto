(ns katello.tests.providers.redhat
  (:require (katello [navigation :as nav]
                     [tasks           :refer :all]
                     [ui-common       :as common]
                     [api-tasks       :as api]
                     [sync-management :as sync]
                     [organizations   :as organization]
                     [manifest        :as manifest]
                     [subscriptions   :as subscriptions]
                     [repositories    :as repo]
                     [changesets      :as changesets]
                     [systems         :as system]
                     [fake-content    :as fake-content]
                     [conf            :refer [config no-clients-defined with-org]]
                     [blockers        :refer [bz-bugs]])
            [test.tree.script :refer [defgroup deftest]]
            [katello.tests.e2e :as e2e]
            [test.assert :as assert]))

;; Constants

(def manifest-tmp-loc (tmpfile "manifest.zip"))

(def bz786963-manifest
  (str (System/getProperty "user.dir") "/manifests/manifest_bz786963.zip"))

;; Functions
(defn step-create-org [{:keys [org-name env-name]}]
  (api/create-organization org-name)
  (when env-name
    (with-org org-name
      (organization/switch)
      (api/create-environment env-name {}))))

(defn verify-all-repos-not-synced [repos]
  (assert/is (every? nil? (map sync/complete-status repos))))

(defn enable-redhat-in-org [org repos]
  (with-org org
    (organization/switch)
    (repo/enable-redhat repos)))

(defn step-clone-manifest [{:keys [manifest-loc]}]
  (manifest/clone manifest-tmp-loc manifest-loc))

(defn step-upload-manifest [{:keys [org-name manifest-loc repository-url] :as m}]
  (with-org org-name
    (organization/switch)
    (api/upload-manifest manifest-loc repository-url)))

(defn step-verify-enabled-repositories [{:keys [org-name enable-repos]}]
  (when (api/is-katello?)
    (with-org org-name
      (organization/switch)
      (repo/enable-redhat enable-repos)
      (nav/go-to :katello.sync-management/status-page)
      (verify-all-repos-not-synced enable-repos))))

(defn step-promote-redhat-content-into-test-env [{:keys [org-name env-name products]}]
  (with-org org-name
    (organization/switch)
    (api/ensure-env-exist env-name {:prior library})
    (when (api/is-katello?)
      (repo/enable-redhat (mapcat :repos products))
      (changesets/sync-and-promote products library env-name))))

(defn step-create-system [{:keys [system-name org-name env-name]}]
  (with-org org-name
    (api/with-env env-name
      (api/create-system system-name {:facts (api/random-facts)}))))

(defn step-set-system-release-version [{:keys [release-version system-name org-name] :as m}]
  (with-org org-name
    (organization/switch)
    (system/edit system-name (select-keys m [release-version]))))

(defn step-verify-client-access [{:keys [org-name env-name products install-packages]}]
  (with-org org-name
    (organization/switch)
    (e2e/test-client-access org-name env-name products install-packages)))

(defn new-fake-manifest []
  {:repository-url (@config :redhat-repo-url)
   :manifest-loc (manifest/new-tmp-loc)})


;; Tests
(defgroup redhat-promoted-content-tests
  (deftest "Admin can set Release Version on system"
    :uuid "cf82309e-8348-c414-4a53-f5ba08648513"
    :blockers (conj (bz-bugs "832192") api/katello-only)

    (do-steps (merge (uniqueify-vals {:system-name "system"
                                      :org-name "relver-test"})
                     (new-fake-manifest)
                     {:org-name (uniqueify "relver-test")
                      :release-version "16"
                      :env-name "Development"
                      :products fake-content/some-product-repos})
              step-create-org
              step-clone-manifest
              step-upload-manifest
              step-promote-redhat-content-into-test-env
              step-create-system
              step-set-system-release-version))

  (deftest "Clients can access Red Hat content"
    :uuid "9db638e6-05bb-d9a4-462b-5114cc970680"
    :description "Enable repositories, promote content into an
                  environment, register a system to that environment
                  and install some packages."
    :blockers (list no-clients-defined)
      
    (do-steps (merge (new-fake-manifest)
                     {:org-name (uniqueify "rh-content-test")
                      :env-name "Development"
                      :products fake-content/some-product-repos
                      :install-packages ["cheetah" "elephant"]
                      :enable-repos (mapcat :repos fake-content/some-product-repos)})
              step-create-org
              step-clone-manifest
              step-upload-manifest
              step-verify-enabled-repositories
              step-verify-client-access))) 

(defgroup redhat-content-provider-tests 
  :blockers (bz-bugs "729364")

  (deftest "Upload a subscription manifest"
    :uuid "60b9676a-c421-3564-1513-b4e38b9bc135"
    (do-steps (merge (new-fake-manifest)
                     {:org-name (uniqueify "manifest-upload")
                      :manifest-loc (manifest/new-tmp-loc)})
              step-create-org
              step-clone-manifest
              step-upload-manifest)
    
               
    (deftest "Enable Red Hat repositories"
      :uuid "b803c8d2-a9e9-8a14-4d63-bb03cfd11328"
      :blockers (list api/katello-only)
      (do-steps (merge (new-fake-manifest)
                       {:org-name (uniqueify "enablerepos")
                        :enable-repos ["Nature Enterprise x86_64 1.0"
                                       "Nature Enterprise x86_64 1.1"]})
                step-create-org
                step-clone-manifest
                step-upload-manifest
                step-verify-enabled-repositories))

    redhat-promoted-content-tests))  

(defn upload-with-redhat-repo [manifest-loc]
  (api/upload-manifest manifest-loc (@config :redhat-repo-url)))

(defgroup manifest-tests
  :group-setup (partial fake-content/download-original manifest-tmp-loc)
  
  (deftest "Upload the same manifest to an org, expecting an error message"
    :uuid "7c3ef15d-1d7f-6f74-8b9b-ed4a239101a5"
    (let [org-name (uniqueify "dup-manifest")
          test-manifest (manifest/new-tmp-loc)]
      (api/create-organization org-name)
      (with-org org-name
        (organization/switch)
        (manifest/clone manifest-tmp-loc test-manifest)
        (upload-with-redhat-repo test-manifest)
        (expecting-error (common/errtype :katello.notifications/import-same-as-existing-data)
                         (upload-with-redhat-repo test-manifest)))))

  (deftest "Upload a previously used manifest into another org"
    :uuid "83596726-1cda-fda4-40d3-e14e9e47ce99"
    (let [two-orgs (take 2 (unique-names "man-reuse"))
          test-manifest (manifest/new-tmp-loc)]
      (doseq [org two-orgs]
        (api/create-organization org))
      (manifest/clone manifest-tmp-loc test-manifest)
      (with-org (first two-orgs)
        (organization/switch)
        (upload-with-redhat-repo test-manifest))
      (with-org (second two-orgs)
        (organization/switch)
        (expecting-error (common/errtype :katello.notifications/distributor-has-already-been-imported)
                         (upload-with-redhat-repo test-manifest)))))
  
  (deftest "Upload manifest tests, testing for number-format-exception-for-inputstring"
    :uuid "0a48ed2d-9e15-d434-37d3-8dd78996ac2a"
    (do-steps {:org-name (uniqueify "bz786963")
               :manifest-loc bz786963-manifest}
              step-create-org
              step-upload-manifest))
 
  (deftest "Upload a manifest and check whether import manifest history gets updated"
    :uuid "779235f4-94f3-fe14-4a6b-eafdbbdc44d3"
    (let [test-org (uniqueify "custom-org")]
      (organization/create test-org)
      (organization/switch test-org)
      (fake-content/prepare-org test-org (take 1 (mapcat :repos fake-content/some-product-repos))))
    (assert/is (subscriptions/upload-manifest-import-history?))))
