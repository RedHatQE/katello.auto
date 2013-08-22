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
              [katello :as kt]
              [test.assert :as assert]
              [test.tree.script :refer [defgroup deftest]]
              [clojure.set :refer [join intersection difference union project select]]))

(declare test-org)

(defn setup []
      (def ^:dynamic test-org (uniqueify (kt/newOrganization {:name "dash-org"})))
      (rest/create test-org)
      (org/switch test-org)
      (fake/prepare-org-custom-provider test-org fake/custom-errata-test-provider)
      (rest/create (kt/newEnvironment {:name (uniqueify "simple-env") :org test-org :prior-env "Library"})))

(defgroup equality 
    :group-setup setup

    (deftest "Repo compare: Differences between repos can be qualified"
      (dashboard/go-top)
      
      (let
         [dashboard-notice (dashboard/get-dashboard-notices)   
          notice (select #(or (= (test-org :name) (% :org))
                             (= "" (% :org))) (notices/page-content))
          comparison (filter (fn [a] (.startsWith (a :desc) (a :short-desc))) (clojure.set/join notice dashboard-notice))] 
          (assert/is
            (=
             (count dashboard-notice) (count notice) (count comparison))
        ))
      ))
