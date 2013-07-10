(ns katello.tests.providers.redhat
  (:require [katello :as kt]
            (katello [navigation :as nav]
                     [tasks           :refer :all]
                     [ui :as ui]
                     [ui-common       :as common]
                     [client :as client]
                     [sync-management :as sync]
                     [rest :as rest]
                     [organizations   :as organization]
                     [manifest        :as manifest]
                     [subscriptions   :as subscriptions]
                     [repositories    :as repo]
                     [changesets      :as changesets]
                     [systems         :as system]
                     [fake-content    :as fake]
                     [rh-repositories :refer [describe-repos-to-enable-disable 
                                              enable-disable-repos
                                              enable-redhat-repos]]
                     [conf :as conf]
                     [blockers        :refer [bz-bugs]])
            [katello.client.provision :as provision]
            [test.tree.script :refer [defgroup deftest]]
            [katello.tests.e2e :as e2e]
            [test.assert :as assert]))

;; Constants

#_(def bz786963-manifest
  (str (System/getProperty "user.dir") "/manifests/manifest_bz786963.zip"))

(def fake-repos fake/enable-nature-repos)

(def redhat-repos enable-redhat-repos)

;; Functions

(defn verify-all-repos-synced [repos]
  (assert/is  (every? #(= "Sync complete." %) (map sync/complete-status repos))))

(defn prepare-org-fetch-org []
  (let [org (uniqueify (kt/newOrganization {:name "redhat-org"}))
        envz (take 3 (uniques (kt/newEnvironment {:name "env", :org org})))]
    (ui/create org)
    (doseq [e (kt/chain envz)]
      (ui/create e))
    org))

(defn new-manifest [redhat-manifest?]
  (let [org       (prepare-org-fetch-org)
        provider  (assoc kt/red-hat-provider :org org)
        fetch-manifest  (uniqueify (manifest/download-original-manifest redhat-manifest?))
        manifest  (assoc fetch-manifest :provider provider)]
    manifest))

;; Tests
(defgroup redhat-promoted-content-tests
  
  (deftest "Admin can set Release Version on system"
    :uuid "cf82309e-8348-c414-4a53-f5ba08648513"
    :blockers (conj (bz-bugs "832192") rest/katello-only)
    (let [org (uniqueify (kt/newOrganization {:name "redhat-org"}))
          envz (take 3 (uniques (kt/newEnvironment {:name "env", :org org})))
          repos (describe-repos-to-enable-disable fake/enable-nature-repos)
          products (->> (map :reposet repos) (map :product) distinct)
          target-env (first envz)
          rel-ver "1.1"]
      (manifest/setup-org envz repos)
      (verify-all-repos-synced repos)
      (-> {:name "cs-manifest" :content products :env target-env} 
          katello/newChangeset uniqueify changesets/promote-delete-content)
      (provision/with-queued-client
          ssh-conn
          (client/register ssh-conn {:username (:name conf/*session-user*)
                                     :password (:password conf/*session-user*)
                                     :org (:name org)
                                     :env (:name (first envz))
                                     :force true})
          (let [mysys (-> {:name (client/my-hostname ssh-conn) :env (first envz)}
                          katello/newSystem)]
            (doseq [prd1 products]
              (client/subscribe ssh-conn (system/pool-id mysys prd1)))
            (client/sm-cmd ssh-conn :refresh)
            (client/run-cmd ssh-conn "yum repolist") 
            (ui/update mysys assoc :release-version rel-ver)))))

  (deftest "Clients can access Red Hat content"
    :uuid "9db638e6-05bb-d9a4-462b-5114cc970680"
    :description "Enable repositories, promote content into an
                  environment, register a system to that environment
                  and install some packages."
    :blockers (list conf/no-clients-defined)      
    (let [org (uniqueify (kt/newOrganization {:name "redhat-org"}))
          envz (take 3 (uniques (kt/newEnvironment {:name "env", :org org})))
          repos (describe-repos-to-enable-disable fake/enable-nature-repos)
          products (->> (map :reposet repos) (map :product) distinct)
          package-to-install "cow"
          target-env (first envz)]
      (manifest/setup-org envz repos)
      (verify-all-repos-synced repos)
      (-> {:name "cs-manifest" :content products :env target-env} 
          katello/newChangeset uniqueify changesets/promote-delete-content)
      (e2e/test-client-access target-env products [package-to-install])))) 
    
(defgroup redhat-content-provider-tests 

  (deftest "Upload a fake subscription manifest"
    :uuid "60b9676a-c421-3564-1513-b4e38b9bc135"
    (let [manifest  (new-manifest false)]
      (ui/create manifest)))
      
         
  (deftest "Enable Fake Repositories of manifest"
    :uuid "b803c8d2-a9e9-8a14-4d63-bb03cfd11328"
    :blockers (list rest/katello-only)
    (let [manifest  (new-manifest false)
          org       (kt/org manifest)
          repos     (for [r (describe-repos-to-enable-disable fake/enable-nature-repos)]
                      (update-in r [:reposet :product :provider] assoc :org org))]
      (ui/create manifest)
      (enable-disable-repos repos)))
    
  (deftest "Verify Sync of Fake Repositories from manifest"
    :uuid "b803c8d2-a9e9-8a14-4d63-bb03cfd10329"
    :blockers (list rest/katello-only)
    (let [org (uniqueify (kt/newOrganization {:name "redhat-org"}))
          envz (take 3 (uniques (kt/newEnvironment {:name "env", :org org})))
          repos (describe-repos-to-enable-disable fake/enable-nature-repos)]
      (manifest/setup-org envz repos)
      (verify-all-repos-synced repos)))

  redhat-promoted-content-tests)

(defgroup manifest-tests
    
  #_(deftest "Upload the same manifest to an org, expecting an error message"
    :uuid "7c3ef15d-1d7f-6f74-8b9b-ed4a239101a5"
    (let [manifest  (new-manifest false)]
      (ui/create manifest)
      (expecting-error (common/errtype :katello.notifications/import-same-as-existing-data)
                         (ui/create manifest))))
  ;; The above testcases passes, because :level shows :message instead of :error or :success,
  ;;when trying to check notifications.

  (deftest "Upload a previously used manifest into another org"
    :uuid "83596726-1cda-fda4-40d3-e14e9e47ce99"
    (let [manifest      (new-manifest false)
          org2          (prepare-org-fetch-org)
          org2-manifest (update-in manifest [:provider] assoc :org org2) ]
      (ui/create manifest)
      (expecting-error (common/errtype :katello.notifications/distributor-has-already-been-imported)
                       (ui/create org2-manifest))))
  
  #_(deftest "Upload manifest tests, testing for number-format-exception-for-inputstring"
    :uuid "0a48ed2d-9e15-d434-37d3-8dd78996ac2a"
    (do-steps {:org-name (uniqueify "bz786963")
               :manifest-loc bz786963-manifest}
              step-create-org
              step-upload-manifest))
  ;;Less idea about this bug and what the above testcase is all about at this stage, 
  ;;need to investigate this bug further.
  
  (deftest "Upload a manifest and check whether import manifest history gets updated"
    :uuid "779235f4-94f3-fe14-4a6b-eafdbbdc44d3"
    (let [manifest  (new-manifest false)]
      (ui/create manifest)
    (assert/is (manifest/upload-manifest-import-history? manifest)))))

