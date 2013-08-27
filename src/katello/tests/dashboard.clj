(ns katello.tests.dashboard
    (:require (katello [tasks         :refer :all]
                       [dashboard :as dashboard]
                       [notices :as notices]
                       [fake-content  :as fake]
                       [organizations :as org]
                       [environments  :as env]
                       [conf          :refer [config with-org]]
                       [changesets :refer [promote-delete-content]]
                       [rest     :as rest]
                       [ui     :as ui]
                       [blockers :refer [bz-bugs]]
                       [fake-content  :as fake])
              [katello.tests.content-views :refer [promote-published-content-view]]
              [katello :as kt]
              [test.assert :as assert]
              [test.tree.script :refer [defgroup deftest]]
              [clojure.set :refer [join intersection difference union project select]]))


(declare test-org-env)
(declare publish-dev)
(declare publish-qa)

(def env-dev "Development")
(def env-qa "QA")
(def env-release "Release")


(defn setup []
   (def ^:dynamic test-org-env  (uniqueify  (kt/newOrganization {:name "env-org"})))
   (rest/create  test-org-env)
   (org/switch test-org-env)
   (fake/prepare-org-custom-provider test-org-env fake/custom-env-test-provider)
   (let [env-dev-r (kt/newEnvironment {:name env-dev :org test-org-env :prior-env "Library"})
         env-qa-r (kt/newEnvironment {:name env-qa :org test-org-env :prior-env env-dev})
         env-release-r (kt/newEnvironment {:name env-release :org test-org-env :prior-env env-qa})]
   (ui/create-all-recursive [env-dev-r env-qa-r  env-release-r])
   (def ^:dynamic publish-dev (:published-name
       (promote-published-content-view 
         test-org-env 
         env-dev-r
         (nth (fake/repo-list-from-tree fake/custom-env-test-provider test-org-env)
              1))))
   (def ^:dynamic publish-qa (:published-name
       (promote-published-content-view 
         test-org-env 
         env-qa-r
         (nth (fake/repo-list-from-tree fake/custom-env-test-provider test-org-env)
              2))))))

(defgroup equality 
    :group-setup setup

    (deftest "Dashboard: Latest Notifications"
      (dashboard/go-top)
      (let
         [dashboard-notice (dashboard/get-dashboard-notices)   
          notice (select #(or (= (test-org :name) (% :org))
                             (= "" (% :org))) (notices/page-content))
          comparison (filter (fn [a] (.startsWith (a :desc) (a :short-desc))) (clojure.set/join notice dashboard-notice))] 
          (assert/is
            (= (count dashboard-notice) (count notice) (count comparison)))))
    
    (deftest "Dashboard: Sync Overview"
      (dashboard/go-top)
      (assert/is (= (project (dashboard/get-dashboard-sync) [:result :product])
                   #{{:result "not_synced", :product "Com Errata Inc"} 
                     {:result "Success", :product "Com Errata Enterprise"} 
                     {:result "Success", :product "Weird Enterprise"}})))
    
    (deftest "Dashboard: Content Views Overview"
       (dashboard/go-top)
       (assert/is (= (project (dashboard/get-dashboard-views) [:result :view])
                     #{{:date "Tue, 27 Aug 2013 05:37:48 +0000", :result "Success", :view publish-dev}
                       {:date "Tue, 27 Aug 2013 05:40:17 +0000", :result "Success", :view publish-qa}})))

    (deftest "Dashboard: Promotions Overview"
      (dashboard/go-top)
      (assert/is (= (project (dashboard/get-dashboard-promotions) [:env :result])
                    #{{:env "Development", :result "Success", :promotion "cs-0827-151641-339"}
                      {:env "QA", :result "Success", :promotion "cs-0827-151855-855"}}))))
