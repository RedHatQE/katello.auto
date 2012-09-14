(ns katello.tests.providers.redhat
  (:require (katello [tasks           :refer :all]
                     [api-tasks       :as api]
                     [sync-management :as sync]
                     [organizations   :as organization]
                     [ui-tasks        :refer [navigate enable-redhat-repositories errtype]]
                     [manifest        :as manifest]
                     [changesets      :as changesets]
                     [systems         :as systems]
                     [fake-content    :as fake-content]
                     [conf            :refer [config no-clients-defined]])
            [test.tree.script :refer [defgroup deftest]]
            [bugzilla.checker :refer [open-bz-bugs]]
            [katello.tests.e2e :as e2e]
            [tools.verify :refer [verify-that]]))

;; Constants

(def manifest-tmp-loc (tmpfile "manifest.zip"))

(def bz786963-manifest
  (str (System/getProperty "user.dir") "/manifests/manifest_bz786963.zip"))

;; Functions
(defn step-create-org [{:keys [org-name]}]
  (api/with-admin (api/create-organization org-name)))

(defn verify-all-repos-not-synced [repos]
  (verify-that (every? nil? (map sync/complete-status repos))))

(defn enable-redhat-repositories-in-org [org repos]
  (organization/execute-with org (enable-redhat-repositories repos)))

(defn step-clone-manifest [{:keys [manifest-loc]}]
  (manifest/clone manifest-tmp-loc manifest-loc))

(defn step-upload-manifest [{:keys [org-name manifest-loc repository-url] :as m}]
  (organization/execute-with org-name
    (manifest/upload manifest-loc (select-keys m :repository-url))))

(defn step-verify-enabled-repositories [{:keys [org-name enable-repos]}]
  (organization/execute-with org-name
    (enable-redhat-repositories enable-repos)
    (navigate :sync-status-page)
    (verify-all-repos-not-synced enable-repos)))

(defn step-promote-redhat-content-into-test-env [{:keys [org-name env-name products]}]
  (api/with-admin
    (api/with-org org-name       
      (api/ensure-env-exist env-name {:prior library})
      (when (api/is-katello?)
        (organization/execute-with org-name
          (enable-redhat-repositories (mapcat :repos products))
          (changesets/sync-and-promote products library env-name))))))

(defn step-create-system [{:keys [system-name org-name env-name]}]
  (api/with-admin
    (api/with-org org-name
      (api/with-env env-name
        (api/create-system system-name {:facts (api/random-facts)})))))

(defn step-set-system-release-version [{:keys [release-version system-name org-name] :as m}]
  (organization/execute-with org-name
    (systems/edit-system system-name (select-keys m [release-version]))))

(defn step-verify-client-access [{:keys [org-name env-name products install-packages]}]
  (api/with-admin
      (api/with-org org-name       
        (e2e/test-client-access org-name env-name products install-packages))))


;; Tests
(defgroup redhat-promoted-content-tests
  (deftest "Admin can set Release Version on system"
    :blockers (open-bz-bugs "832192")

    (do-steps (merge (uniqueify-vals {:system-name "system"
                                      :org-name "relver-test"})
                     {:release-version "16"
                      :env-name "Development"
                      :products fake-content/some-product-repos
                      :repository-url (@config :redhat-repo-url)})
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
      
    (do-steps (merge (uniqueify-vals {:system-name "system"
                                      :org-name "relver-test"})
                     {:env-name "Development"
                      :products fake-content/some-product-repos
                      :repository-url (@config :redhat-repo-url)
                      :install-packages ["cheetah" "elephant"] })
              step-create-org
              step-clone-manifest
              step-upload-manifest
              step-verify-client-access))) 

(defgroup redhat-content-provider-tests 
  :blockers    (open-bz-bugs "729364")

  (deftest "Upload a subscription manifest"
    (do-steps {:org-name (uniqueify "manifest-upload")}
              step-create-org
              step-clone-manifest
              step-upload-manifest)
    
               
    (deftest "Enable Red Hat repositories"
      :blockers api/katello-only
      (do-steps {:org-name (uniqueify "enablerepos")
                 :enable-repos ["Nature Enterprise x86_64 1.0"
                                "Nature Enterprise x86_64 1.1"]}
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
      (api/with-admin (api/create-organization org-name))
      (organization/execute-with org-name
        (manifest/clone manifest-tmp-loc test-manifest)
        (upload test-manifest)
        (expecting-error (errtype :katello.notifications/import-older-than-existing-data)
                         (upload test-manifest)))))

  (deftest "Upload a previously used manifest into another org"
    (let [two-orgs (take 2 (unique-names "man-reuse"))
          test-manifest (manifest/new-tmp-loc)
          upload (fn [loc]
                   (manifest/upload loc {:repository-url
                                         (@config :redhat-repo-url)}))]
      (api/with-admin (doseq [org two-orgs]
                        (api/create-organization org)))
      (manifest/clone manifest-tmp-loc test-manifest)
      (organization/execute-with (first two-orgs)
        (upload test-manifest))
      (organization/execute-with (second two-orgs)
        (expecting-error (errtype :katello.notifications/distributor-has-already-been-imported)
                         (upload test-manifest)))))
  
  (deftest "Upload manifest tests, testing for number-format-exception-for-inputstring"
    (do-steps {:org-name (uniqueify "bz786963")
               :manifest-loc bz786963-manifest}
              step-create-org
              step-upload-manifest)))







