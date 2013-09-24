(ns katello.tests.suite
  (:refer-clojure :exclude [fn])
  (:require (katello.tests organizations providers promotions
                           sync_management login environments
                           systems system-groups activation-keys
                           users permissions e2e navigation search
                           distributors content-search content-views)
            ;;katello.tests.providers.custom
            katello.tests.providers.redhat
            [katello.client.provision :as provision]
            [katello.setup :as setup]
            [katello.conf :as conf :refer [config]]
            [katello.login :as login]
            [clojure.tools.cli :as cli]
            [clj-webdriver.taxi :as browser]
            [webdriver :as wd]
            [serializable.fn :refer :all] 
            [test.tree.jenkins :as jenkins]
            [test.tree.script :refer :all]
            [test.tree.builder :as ttb])
  (:import [org.openqa.selenium.remote SessionNotFoundException]
           [org.openqa.selenium InvalidElementStateException]))

(defgroup katello-tests
  

  katello.tests.login/login-tests
  katello.tests.navigation/nav-tests
  katello.tests.organizations/org-tests
  katello.tests.search/search-tests
  katello.tests.environments/environment-tests
  katello.tests.providers/provider-tests
  katello.tests.distributors/distributor-tests
  katello.tests.promotions/promotion-tests
  ;; katello.tests.promotions/deletion-tests  ;; needs to be added back
  katello.tests.permissions/permission-tests
  ;;katello.tests.systems/system-tests
  ;;katello.tests.system-groups/sg-tests
  katello.tests.activation-keys/ak-tests
  katello.tests.sync_management/sync-tests
  katello.tests.users/user-tests
  katello.tests.e2e/end-to-end-tests
  katello.tests.providers.redhat/manifest-tests
  katello.tests.providers.redhat/redhat-content-provider-tests
  ;;katello.tests.providers.custom/custom-product-tests
  katello.tests.content-search/content-search-tests
  katello.tests.content-views/content-views-tests
  )


(defgroup headpin-tests
  :description "All the tests that apply to headpin or SAM."
  
  katello.tests.login/login-tests
  katello.tests.navigation/nav-tests
  katello.tests.organizations/org-tests
  katello.tests.search/search-tests
  katello.tests.distributors/distributor-tests
  katello.tests.systems/system-tests
  katello.tests.system-groups/sg-tests
  katello.tests.activation-keys/ak-tests
  katello.tests.users/user-tests
  katello.tests.providers.redhat/manifest-tests)

(defn make-suite
  ([] (make-suite nil))
  ([group]
     (try (ttb/run-before (constantly true) (fn [& _] (setup/conf-selenium))
           (-> group (or "katello.tests.suite/katello-tests")
               symbol resolve deref))
          (catch Exception e
            (throw (RuntimeException.
                    (format "Could not find any test suite named %s. Please specify a fully qualified symbol whose value contains a test suite, eg 'katello.tests.suite/katello-tests'." group) e))))))

(defn -main [ & args]
  (let [[opts [suite] banner]
        (apply cli/cli args conf/options)]
    (if (:help opts)
      (do (println banner))
      (do
        (conf/init opts)
        (let [client-queue (provision/init 3)]
          (try (jenkins/run-suite (make-suite suite)  
                                  (merge setup/runner-config 
                                         {:threads (:num-threads opts)
                                          :trace-depths-fn conf/trace-list
                                          :to-trace (@conf/config :trace)
                                          :do-not-trace (@conf/config :trace-excludes)
                                          :middleware (setup/harness-middleware)}))
               (finally (provision/shutdown client-queue)
                        (-> conf/*cloud-conn* :api .shutdown))))))))
