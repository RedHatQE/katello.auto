(ns katello.tests.distributors
  (:refer-clojure :exclude [fn])
  (:require [katello :as kt]
            (katello [tasks :refer :all]
                     [rest :as rest]
                     [ui :as ui]
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
        (ui/delete dist1)))))
