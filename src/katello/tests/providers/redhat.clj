(in-ns 'katello.tests.providers)

;; Variables

(def manifest-tmp-loc "/tmp/manifest.zip")
(def redhat-provider-test-org (atom nil))

;; Functions

(defn prepare-manifest-and-org []
  (let [org-name (reset! redhat-provider-test-org (uniqueify "rh-test"))]
    (api/with-admin (api/create-organization org-name))
    (with-open [instream (io/input-stream (java.net.URL. (@config :redhat-manifest-url)))
                outstream (io/output-stream manifest-tmp-loc)]
      (io/copy instream outstream))))

(defn verify-all-repos-not-synced [repos]
  (verify-that (every? nil? (map sync-complete-status repos))))

;; Tests

(defgroup redhat-content-provider-tests
  :group-setup prepare-manifest-and-org 


  (deftest "upload-manifest"
    :description "Upload manifest, uses API currently, if I remember
                  right, the UI no longer allows selenium to select
                  the manifest file."
    :what-test-should-do (comment (with-org @redhat-provider-test-org
                                    (upload-subscription-manifest manifest-tmp-loc
                                                                  {:repository-url (@config :redhat-repo-url)})))

    (api/with-admin (api/with-org @redhat-provider-test-org
                      (api/upload-manifest manifest-tmp-loc (@config :redhat-repo-url))))


    (deftest "enable-redhat-repos"  
      (let [repos ["Nature Enterprise x86_64 5Server" "Nature Enterprise x86_64 6Server"]]
        (with-org @redhat-provider-test-org
          (enable-redhat-repositories repos)
          (navigate :sync-status-page)
          (verify-all-repos-not-synced repos))))

    (comment e2e/client-access-redhat)))







