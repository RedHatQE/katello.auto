(in-ns 'katello.tests.providers)

;; Variables

(def manifest-tmp-loc "/tmp/manifest.zip")

(def redhat-provider-test-org (atom nil))

(def redhat-products [{:name "Nature Enterprise"
                       :poolName "Nature Enterprise 8/5"
                       :repos ["Nature Enterprise x86_64 6Server"
                               "Nature Enterprise x86_64 5Server"]}
                      #_{:name "Zoo Enterprise"
                         :poolName "Zoo Enterprise 24/7"
                         :repos ["Zoo Enterprise x86_64 6Server"
                                 "Zoo Enterprise x86_64 5Server"]}])

(def redhat-repos (apply concat (map :repos redhat-products)))

(def packages-to-install ["cheetah" "elephant"])


;; Functions

(defn prepare-manifest-and-org []
  (let [org-name (reset! redhat-provider-test-org (uniqueify "rh-test"))]
    (api/with-admin (api/create-organization org-name))
    (with-open [instream (io/input-stream (java.net.URL. (@config :redhat-manifest-url)))
                outstream (io/output-stream manifest-tmp-loc)]
      (io/copy instream outstream))))

(defn verify-all-repos-not-synced [repos]
  (verify-that (every? nil? (map sync-complete-status repos))))

(defn enable-redhat-repositories-in-org [org repos]
  (with-org @redhat-provider-test-org (enable-redhat-repositories redhat-repos)))

(defn upload-test-manifest-to-test-org [& [opts]]
  (with-org @redhat-provider-test-org
    (upload-subscription-manifest manifest-tmp-loc
                                  (merge {:repository-url (@config :redhat-repo-url)}
                                         opts))))

;; Tests

(defgroup redhat-content-provider-tests
  :group-setup prepare-manifest-and-org 
  :blockers (open-bz-bugs "729364")

  (deftest "Upload a subscription manifest"
    (upload-test-manifest-to-test-org)            

    (deftest "Upload the same manifest to an org using force"
      (upload-test-manifest-to-test-org {:force true}))

    (deftest "Enable Red Hat repositories"
      :blockers api/katello-only
      
      (let [repos ["Nature Enterprise x86_64 15" "Nature Enterprise x86_64 16"]]
        (with-org @redhat-provider-test-org
          (enable-redhat-repositories repos)
          (navigate :sync-status-page)
          (verify-all-repos-not-synced repos))))
    

    (deftest "Clients can access Red Hat content"
      :description "Enable repositories, promote content into an
                    environment, register a system to that environment
                    and install some packages."
      :blockers no-clients-defined
      
      (with-org @redhat-provider-test-org
        (when (api/is-katello?)
          (enable-redhat-repositories redhat-repos))
        (api/with-admin
          (api/with-org @redhat-provider-test-org
            (with-unique [target-env "myenv"]
              (api/ensure-env-exist target-env {:prior library})
              (test-client-access @redhat-provider-test-org
                                  target-env
                                  redhat-products
                                  packages-to-install))))))))







