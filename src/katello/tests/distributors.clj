(ns katello.tests.distributors
  (:refer-clojure :exclude [fn])
  (:require [katello :refer [newOrganization]]
            (katello [tasks :refer :all]
                     [rest :as rest]
                     [distributors :as distributor])
            (test.tree [script :refer [defgroup deftest]])
            [serializable.fn :refer [fn]]
            [test.assert :as assert]))

(defgroup distributor-tests

  (deftest "+New Distributor button is disabled for an organization with no environments"
    (with-unique [org (newOrganization {:name "auto-org"})]
      (rest/create org)
      (distributor/new-distributor-button-disabled? org))))
