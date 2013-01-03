(ns katello.tests.providers.redhat
  (:require (katello [tasks           :refer :all]
                     [api-tasks       :as api]
                     [sync-management :as sync]
                     [organizations   :as organization]
                     [ui-tasks        :refer [navigate enable-redhat-repositories errtype]]
                     [manifest        :as manifest]
                     [changesets      :as changesets]
                     [systems         :as system]
                     [fake-content    :as fake-content]
                     [conf            :refer [config no-clients-defined with-org]])
            [test.tree.script :refer [defgroup deftest]]
            [test.tree.builder :refer [union]]
            [bugzilla.checker :refer [open-bz-bugs]]
            [katello.tests.e2e :as e2e]
            [tools.verify :refer [verify-that]]))

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
  (verify-that (every? nil? (map sync/complete-status repos))))

(defn enable-redhat-repositories-in-org [org repos]
  (with-org org
    (organization/switch)
    (enable-redhat-repositories repos)))

(defn step-clone-manifest [{:keys [manifest-loc]}]
  (manifest/clone manifest-tmp-loc manifest-loc))

(defn step-upload-manifest [{:keys [org-name manifest-loc repository-url] :as m}]
  (with-org org-name
    (organization/switch)
    (manifest/upload manifest-loc (select-keys m [:repository-url]))))

(defn step-verify-enabled-repositories [{:keys [org-name enable-repos]}]
  (when (api/is-katello?)
    (with-org org-name
      (organization/switch)
      (enable-redhat-repositories enable-repos)
      (navigate :sync-status-page)
      (verify-all-repos-not-synced enable-repos))))

(defn step-promote-redhat-content-into-test-env [{:keys [org-name env-name products]}]
  (with-org org-name
    (organization/switch)
    (api/ensure-env-exist env-name {:prior library})
    (when (api/is-katello?)
      (enable-redhat-repositories (mapcat :repos products))
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
    :blockers (union (open-bz-bugs "832192")
                     api/katello-only)

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
    :description "Enable repositories, promote content into an
                  environment, register a system to that environment
                  and install some packages."
    :blockers no-clients-defined
      
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
  :blockers    (open-bz-bugs "729364")

  (deftest "Upload a subscription manifest"
    (do-steps (merge (new-fake-manifest)
                     {:org-name (uniqueify "manifest-upload")
                      :manifest-loc (manifest/new-tmp-loc)})
              step-create-org
              step-clone-manifest
              step-upload-manifest)
    
               
    (deftest "Enable Red Hat repositories"
      :blockers api/katello-only
      (do-steps (merge (new-fake-manifest)
                       {:org-name (uniqueify "enablerepos")
                        :enable-repos ["Nature Enterprise x86_64 1.0"
                                       "Nature Enterprise x86_64 1.1"]})
                step-create-org
                step-clone-manifest
                step-upload-manifest
                step-verify-enabled-repositories))

    redhat-promoted-content-tests))  

(defgroup manifest-tests
  :group-setup (partial fake-content/download-original manifest-tmp-loc)
  
  (deftest "Upload the same manifest to an org, expecting an error message"	  	
    (let [org-name (uniqueify "dup-manifest")
          test-manifest (manifest/new-tmp-loc)
          upload #(manifest/upload % {:repository-url
                                      (@config :redhat-repo-url)})]
      (api/create-organization org-name)
      (with-org org-name
        (organization/switch)
        (manifest/clone manifest-tmp-loc test-manifest)
        (upload test-manifest)
        (expecting-error (errtype :katello.notifications/import-same-as-existing-data)
                         (upload test-manifest)))))

  (deftest "Upload a previously used manifest into another org"
    (let [two-orgs (take 2 (unique-names "man-reuse"))
          test-manifest (manifest/new-tmp-loc)
          upload (fn [loc]
                   (manifest/upload loc {:repository-url
                                         (@config :redhat-repo-url)}))]
      (doseq [org two-orgs]
        (api/create-organization org))
      (manifest/clone manifest-tmp-loc test-manifest)
      (with-org (first two-orgs)
        (organization/switch)
        (upload test-manifest))
      (with-org (second two-orgs)
        (organization/switch)
        (expecting-error (errtype :katello.notifications/distributor-has-already-been-imported)
                         (upload test-manifest)))))
  
  (deftest "Upload manifest tests, testing for number-format-exception-for-inputstring"
    (do-steps {:org-name (uniqueify "bz786963")
               :manifest-loc bz786963-manifest}
              step-create-org
              step-upload-manifest)))







