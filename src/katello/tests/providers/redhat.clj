(in-ns 'katello.tests.providers)

;; Variables

;;(defn fully-qualify [file]
;;  (str (System/getProperty "user.dir") file))

(def manifest-tmp-loc "/tmp/manifest.zip")

(def org1-m1-manifest
  (str (System/getProperty "user.dir") "/manifests/manifest_D1_O1_M1.zip"))
(def org2-m1-manifest 
;;  (fully-qualify "/manifests/manifest_D2_O2_M1.zip"))
  (str (System/getProperty "user.dir") "/manifests/manifest_D2_O2_M1.zip"))
(def org4-m1-manifest
  (str (System/getProperty "user.dir") "/manifests/manifest_D4_O4_M1.zip"))
(def scenario2-m1-d1-manifest
  (str (System/getProperty "user.dir") "/manifests/scenario2_M1_D1.zip"))
(def scenario5-o1-m2-manifest
  (str (System/getProperty "user.dir") "/manifests/scenario5_O1_M2.zip"))
(def scenario5-o1-m1-manifest
  (str (System/getProperty "user.dir") "/manifests/scenario5_O1_M1.zip"))
(def bz786963-manifest
  (str (System/getProperty "user.dir") "/manifests/manifest_bz786963.zip"))




(def redhat-provider-test-org (atom nil))
(def redhat-provider-test-org2 (atom nil))
(def redhat-provider-test-org3 (atom nil))
(def redhat-provider-test-org4 (atom nil))
(def redhat-provider-test-org5 (atom nil))

(def redhat-provider-test-env (atom nil))

(def redhat-products [{:name       "Nature Enterprise"
                       :poolName   "Nature Enterprise 8/5"
                       :repos      ["Nature Enterprise x86_64 6Server"
                                    "Nature Enterprise x86_64 5Server"
                                    "Nature Enterprise x86_64 16"]}
                      #_{:name     "Zoo Enterprise"
                         :poolName "Zoo Enterprise 24/7"
                         :repos    ["Zoo Enterprise x86_64 6Server"
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

(defn prepare-org [a]
  (let [org-name (uniqueify "rh-manifest-test")]
    (reset! a org-name)
    (api/with-admin (api/create-organization org-name))))

(defn verify-all-repos-not-synced [repos]
  (verify-that (every? nil? (map sync-complete-status repos))))

(defn enable-redhat-repositories-in-org [org repos]
  (with-org @redhat-provider-test-org (enable-redhat-repositories redhat-repos)))


(defn upload-test-manifest-to-test-org [& [opts]]
  (with-org @redhat-provider-test-org
    (upload-subscription-manifest manifest-tmp-loc
                                  (merge {:repository-url (@config :redhat-repo-url)}
                                         opts))))


(defn upload-test-manifest [manifest-loc org opts]
  (with-org org
    (upload-subscription-manifest manifest-loc
                                  (merge {:repository-url (@config :redhat-repo-url)}
                                         opts))))

(defn promote-redhat-content-into-test-env []
  (api/with-admin
    (api/with-org @redhat-provider-test-org       
      (let [target-env (reset! redhat-provider-test-env (uniqueify "redhat"))]
        (api/ensure-env-exist target-env {:prior library})
        (when (api/is-katello?)
          (with-org @redhat-provider-test-org
            (enable-redhat-repositories redhat-repos)
            (sync-and-promote redhat-products library target-env)))))))

;; Tests

(defgroup redhat-promoted-content-tests
  :group-setup promote-redhat-content-into-test-env

  (deftest "Admin can set Release Version on system"
    :blockers (open-bz-bugs ["832192"])
    
    (with-unique [system-name "system"]
      (api/with-admin
        (api/with-org @redhat-provider-test-org
          (api/with-env @redhat-provider-test-env
            (api/create-system system-name {:facts (api/random-facts)}))))
      (edit-system system-name {:release-version "16"})))
  

  (deftest "Clients can access Red Hat content"
    :description "Enable repositories, promote content into an
                    environment, register a system to that environment
                    and install some packages."
    :blockers no-clients-defined
      
    (api/with-admin
      (api/with-org @redhat-provider-test-org       
        (test-client-access @redhat-provider-test-org
                            @redhat-provider-test-env
                            redhat-products
                            packages-to-install)))))


(defgroup redhat-provider-one-org-multiple-manifest-tests
  :group-setup  (fn []
                  (prepare-org redhat-provider-test-org2)
                  (upload-test-manifest scenario5-o1-m1-manifest
                                        @redhat-provider-test-org2 {}))
  
  (deftest "Upload the same manifest to an org, expecting an error message"	  	
    (expecting-error (errtype :katello.ui-tasks/import-older-than-existing-data)
                     (upload-test-manifest scenario5-o1-m1-manifest @redhat-provider-test-org2)))
  
  (deftest "Load New manifest into same org"
    (upload-test-manifest scenario5-o1-m2-manifest @redhat-provider-test-org2 {})))

(defgroup redhat-provider-second-org-one-manifest-tests
  :group-setup (partial prepare-org redhat-provider-test-org3)
  
  (deftest "Upload a manifest into a second org"
    (upload-test-manifest org2-m1-manifest @redhat-provider-test-org3 {})))

(defgroup redhat-provider-used-manifest-tests
  :group-setup (partial prepare-org redhat-provider-test-org4)
  
  (deftest "Upload a previously used manifest into another org"
    (expecting-error (errtype :katello.ui-tasks/distributor-has-already-been-imported)
      (upload-test-manifest scenario5-o1-m1-manifest @redhat-provider-test-org4 {}))))

(defgroup redhat-provider-other-manifest-tests
  :group-setup (partial prepare-org redhat-provider-test-org5)
  :blockers (open-bz-bugs "786963")
  
  (deftest "Upload manifest tests, testing for number-format-exception-for-inputstring"
    (upload-test-manifest bz786963-manifest @redhat-provider-test-org5 {})))
  

(defgroup redhat-content-provider-tests
  :group-setup prepare-manifest-and-org 
  :blockers    (open-bz-bugs "729364")

  (deftest "Upload a subscription manifest"
    (upload-test-manifest-to-test-org)            

    (deftest "Enable Red Hat repositories"
      :blockers api/katello-only
      
      (let [repos ["Nature Enterprise x86_64 15" "Nature Enterprise x86_64 16"]]
        (with-org @redhat-provider-test-org
          (enable-redhat-repositories repos)
          (navigate :sync-status-page)
          (verify-all-repos-not-synced repos))))

    redhat-promoted-content-tests))







