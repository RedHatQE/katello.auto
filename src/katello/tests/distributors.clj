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
    (with-unique [org  (kt/newOrganization {:name "test-org"
                                            :initial-env (kt/newEnvironment {:name "dev"})})
                  dist (kt/newDistributor {:name "test-dist" :env (:initial-env org)})]
      (ui/create-all (list org dist))))

  (deftest "Delete a Distributor"
    (with-unique [org  (kt/newOrganization {:name "test-org"
                                            :initial-env (kt/newEnvironment {:name "dev"})})
                  dist (kt/newDistributor {:name "test-dist" :env (:initial-env org)})]
      (ui/create-all (list org dist))
      (ui/delete dist))))
