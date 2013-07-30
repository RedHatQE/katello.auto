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
                     [content-view-definitions :as views]
                     [repositories    :as repo]
                     [changesets      :as changesets]
                     [systems         :as system]
                     [fake-content    :as fake]
                     [redhat-repositories :refer [describe-repos-to-enable-disable 
                                              enable-disable-repos
                                              enable-redhat-repos]]
                     [conf :as conf]
                     [blockers        :refer [bz-bugs]])
            [slingshot.slingshot :refer [try+]]
            [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            [katello.client.provision :as provision]
            [test.tree.script :refer [defgroup deftest]]
            [katello.tests.e2e :as e2e]
            [test.assert :as assert]))

;; Constants

(def eus-manifest-url  "http://cosmos.lab.eng.pnq.redhat.com/rhel64/redhat-manifest-eus.zip")

(def non-standard-manifest-url  "http://cosmos.lab.eng.pnq.redhat.com/rhel64/redhat-manifest-all.zip")

(def hacked-manifest-url "http://cosmos.lab.eng.pnq.redhat.com/rhel64/hacked-manifest.zip")

(def fake-repos fake/enable-nature-repos)

(def redhat-repos enable-redhat-repos)

(def non-standard-map
  {:rhel            "Red Hat Employee Subscription"
   :hpn             "90 Day Supported High Performance Network (4 sockets) Evaluation"
   :cloud-forms     "CloudForms Employee Subscription"
   :open-shift      "OpenShift Employee Subscription"
   :cloud-providers "Red Hat Enterprise Linux for Cloud Providers, Partner Enablement, Premium"
   :hcn             "Red Hat Enterprise Linux Server for HPC Compute Node, Self-support (8 sockets) (Up to 1 guest)"
   :rhev            "Red Hat Enterprise Virtualization for Desktops (25 concurrent desktops), Premium"
   :jboss           "Red Hat JBoss Enterprise Application Platform ELS Program, 64 Core Standard"
   :scalable-hcn    "Scalable File System for HPC Compute Node (1-2 sockets)"})

;; extended update support (eus), part of non-standard subscriptions manifest,
;; couln't generate it with the above one, got it seperately.

(def eus-map  
  {:eus          "Extended Update Support for Red Hat Enterprise Linux Server (8 sockets)"})

;; Functions

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

(defn all-subs-exist?
  [map manifest]
  (nav/go-to ::subscriptions/page (kt/provider manifest))
  (every? true? (for [subscription (keys map)]
                  (browser isElementPresent (subscriptions/subs-exists (map subscription))))))

(defn extract-manifest-history-list [page manifest]
  (nav/go-to page manifest)
  (common/extract-list subscriptions/fetch-all-history))
  
;; Tests
(defgroup redhat-promoted-content-tests
  
  (deftest "Admin can set Release Version on system"
    :uuid "cf82309e-8348-c414-4a53-f5ba08648513"
    :blockers (conj (bz-bugs "832192") rest/katello-only)
    (let [org (uniqueify (kt/newOrganization {:name "redhat-org"}))
          envz (take 3 (uniques (kt/newEnvironment {:name "env", :org org})))
          repos (describe-repos-to-enable-disable fake-repos)
          products (->> (map :reposet repos) (map :product) distinct)
          target-env (first envz)
          rel-ver "1.1"
          cv (-> {:name "content-view" :org org :published-name "publish-name"}
                             kt/newContentView uniqueify)
          cs (-> {:name "cs" :env target-env :content (list cv)}
                             kt/newChangeset uniqueify)]
      (manifest/setup-org envz repos)
      (sync/verify-all-repos-synced repos)
      (ui/create cv)
      (ui/update cv assoc :products products)
      (views/publish {:content-defn cv
                      :published-name (:published-name cv)
                      :description "test pub"
                      :org org})
      (changesets/promote-delete-content cs)
      (provision/with-queued-client
          ssh-conn
          (client/register ssh-conn {:username (:name conf/*session-user*)
                                     :password (:password conf/*session-user*)
                                     :org (:name org)
                                     :env (:name target-env)
                                     :force true})
          (let [mysys (-> {:name (client/my-hostname ssh-conn) :env target-env}
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
          repos (describe-repos-to-enable-disable fake-repos)
          products (->> (map :reposet repos) (map :product) distinct)
          package-to-install "cow"
          target-env (first envz)
          cv (-> {:name "content-view" :org org :published-name "publish-name"}
                             kt/newContentView uniqueify)
          cs (-> {:name "cs" :env target-env :content (list cv)}
                             kt/newChangeset uniqueify)]
      (manifest/setup-org envz repos)
      (sync/verify-all-repos-synced repos)
      (ui/create cv)
      (ui/update cv assoc :products products)
      (views/publish {:content-defn cv
                      :published-name (:published-name cv)
                      :description "test pub"
                      :org org})
      (changesets/promote-delete-content cs)
      (e2e/test-client-access target-env products [package-to-install])))) 
    
(defgroup redhat-content-provider-tests 

  (deftest "Upload a fake and real subscription manifest"
    :uuid "60b9676a-c421-3564-1513-b4e38b9bc135"
    :data-driven true
    (fn [redhat-manifest?]
      (let [manifest  (new-manifest redhat-manifest?)]
        (ui/create manifest)))
    [[false]
     [true]])
      
         
  (deftest "Enable Fake and Real Repositories of manifest"
    :uuid "b803c8d2-a9e9-8a14-4d63-bb03cfd11328"
    :blockers (list rest/katello-only)
    :data-driven true 
    (fn [redhat-manifest? repos]
      (let [manifest  (new-manifest redhat-manifest?)
            org       (kt/org manifest)
            repos     (for [r (describe-repos-to-enable-disable repos)]
                        (update-in r [:reposet :product :provider] assoc :org org))]
        (ui/create manifest)
        (enable-disable-repos repos)))
    [[false fake-repos]
     [true redhat-repos]])
    
  (deftest "Verify Sync of Fake and Real Repositories from manifest"
    :uuid "b803c8d2-a9e9-8a14-4d63-bb03cfd10329"
    :blockers (list rest/katello-only)
    :data-driven true
    (fn [repos]
      (let [org (uniqueify (kt/newOrganization {:name "redhat-org"}))
            envz (take 3 (uniques (kt/newEnvironment {:name "env", :org org})))
            repos (describe-repos-to-enable-disable repos)]
        (manifest/setup-org envz repos)
        (sync/verify-all-repos-synced repos)))
    [[fake-repos]
     [redhat-repos]])

  redhat-promoted-content-tests)

(defgroup manifest-tests
    
  #_(deftest "Upload the same manifest to an org, expecting an error message"
    :uuid "7c3ef15d-1d7f-6f74-8b9b-ed4a239101a5"
    (let [manifest  (new-manifest false)]
      (ui/create manifest)
      (try+ (ui/create manifest)
            (catch (common/errtype :katello.notifications/import-same-as-existing-data) _ nil))))
  ;; The above testcases passes, because :level shows :message instead of :error or :success,
  ;;when trying to check notifications.
  
  (deftest " Upload of Subscription Manifest, Empty/Invalid manifest file"
    :uuid "7c3ad15d-1d7f-6f74-8b9b-ed4a239111a6"
    (let [manifest       (new-manifest false)
          file-path      "/tmp/manifest_empty.zip"
          empty-manifest (assoc manifest :file-path file-path)]
      (spit "/tmp/manifest_empty.zip" "")
      (expecting-error (common/errtype :katello.notifications/distributor-invalid-or-empty)
                         (ui/create empty-manifest))
      (ui/create manifest)))

  (deftest "Upload a previously used manifest into another org"
    :uuid "83596726-1cda-fda4-40d3-e14e9e47ce99"
    (let [manifest      (new-manifest false)
          org2          (prepare-org-fetch-org)
          org2-manifest (update-in manifest [:provider] assoc :org org2) ]
      (ui/create manifest)
      (expecting-error (common/errtype :katello.notifications/distributor-has-already-been-imported)
                       (ui/create org2-manifest))))
  
  (deftest "Upload a manifest and check whether import-history-page and 
            manifest-history-page get updated"
    :uuid "779235f4-94f3-fe14-4a6b-eafdbbdc44d3"
    :data-driven true
    (fn [page]
      (let [manifest  (new-manifest false)]
        (ui/create manifest)
        (assert/is (= 1 (count (extract-manifest-history-list page manifest))))))
    [[::subscriptions/import-history-page]
     [::subscriptions/manifest-history-page]])
  
  (deftest "Upload two different manifests and 
            check whether import manifest history gets updated for both"
    :uuid "51de818b-eda3-46b1-b8d8-dfecd712875c"
    (let [manifest   (new-manifest false)
          org        (kt/org manifest)
          manifest-2 (new-manifest true)
          org-manifest-2 (update-in manifest-2 [:provider] assoc :org org)]
      (ui/create manifest)
      (expecting-error (common/errtype :katello.notifications/already-imported-another-manifest)
                       (ui/create org-manifest-2))
      (assert/is (= 2 (count (extract-manifest-history-list ::subscriptions/import-history-page manifest))))))
  
  (deftest "Upload non-standard manifests and
            check whether all subscriptions are visible"
    :uuid "0a48ed2d-9e15-d434-37d3-8dd78996ac2a"
    :data-driven true
    (fn [manifest-url subs-map]
      (let [org (prepare-org-fetch-org)
            provider (assoc kt/red-hat-provider :org org)
            dest (manifest/fetch-manifest manifest-url)
            manifest (uniqueify (kt/newManifest {:file-path dest
                                                 :url (@conf/config :redhat-repo-url)
                                                 :provider provider}))]     
        (ui/create manifest)
        (assert/is (all-subs-exist? subs-map manifest))))
    [[eus-manifest-url eus-map]
     [non-standard-manifest-url non-standard-map]])
  
  (deftest "Upload a hacked manifest"
    :uuid "c646cc3b-6e84-44fc-8beb-403fb2e8113a"
    (let [org (prepare-org-fetch-org)
          provider (assoc kt/red-hat-provider :org org)
          dest (manifest/fetch-manifest hacked-manifest-url)
          manifest (kt/newManifest {:file-path dest
                                               :url (@conf/config :redhat-repo-url)
                                               :provider provider})
          manifest-2      (new-manifest false)]
      (expecting-error (common/errtype :katello.notifications/failed-signature-check)
                         (ui/create manifest))
      (ui/create manifest-2)))
  
  (deftest "Delete a manifest"
    :uuid "60b9676a-d420-3564-1666-b4e3ff9b3885"
    (let [manifest  (new-manifest false)]
      (ui/create manifest)
      (ui/delete manifest)))
  
  (deftest "Upload a fake manifest, delete it and upload real manifest"
    :uuid "60b9676a-d420-3564-1555-b4e38b9b3335"
    (let [manifest-1  (new-manifest false)
          manifest-2  (new-manifest true)]
      (ui/create manifest-1)
      (ui/delete manifest-1)
      (ui/create manifest-2)))
  
  (deftest "Delete a manifest from an ORG and upload the same 
            manifest to another ORG"
    :uuid "3737658d-3924-4df1-86fa-272a8b9d8b72"
    (let [manifest      (new-manifest false)
          org2          (prepare-org-fetch-org)
          org2-manifest (update-in manifest [:provider] assoc :org org2) ]
      (ui/create manifest)
      (ui/delete manifest)
      (ui/create org2-manifest))))
