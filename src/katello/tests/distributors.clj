(ns katello.tests.distributors
  (:refer-clojure :exclude [fn])
  (:require [katello :as kt]
            (katello [tasks :refer :all]
                     [rest :as rest]
                     [ui :as ui]
                     [navigation :as nav]
                     [ui-common :as common]
                     [notifications :as notification]
                     [distributors :as distributor])
            (test.tree [script :refer [defgroup deftest]])
            [serializable.fn :refer [fn]]
            [test.assert :as assert]))

(defgroup distributor-tests

  (deftest "Create a Distributor"
    (with-unique [org  (kt/newOrganization {:name "test-org"})
                  dist (kt/newDistributor {:name "test-dist"})]
      (rest/create org)
      (ui/create (assoc dist :env (kt/newEnvironment {:name "Library" :org org})))))

  (deftest "Delete a Distributor"
    (with-unique [org  (kt/newOrganization {:name "test-org"})
                  dist (kt/newDistributor {:name "test-dist"})]
      (let [dist1 (assoc dist :env (kt/newEnvironment {:name "Library" :org org}))]
        (rest/create org)
        (ui/create dist1)
        (ui/delete dist1))))
  
  (deftest "Update custom info for a distributor"   
    :data-driven true
    (fn [input-loc new-value save?]
      (with-unique [org (kt/newOrganization {:name "auto-org"})
                    env (kt/newEnvironment {:name "environment" :org org})
                    dist (kt/newDistributor {:name "test-dist" :env env})]
        (let [expected-res #(-> % :type (= :success))]
          (ui/create-all (list org env dist))
          (ui/update dist assoc :keyname "fname" :value "redhat")
          (expecting-error expected-res
            (nav/go-to ::distributor/custom-info-page dist)              
            (common/save-cancel ::distributor/save-button ::distributor/cancel-button input-loc new-value save?)))))
    
    [[(distributor/value-text "fname") "fedora" false]
     [(distributor/value-text "fname") "Schrodinger's cat" true]]))
