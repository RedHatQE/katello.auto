(ns katello.tests.distributors
  (:refer-clojure :exclude [fn])
  (:require (katello [api-tasks :as api]
                     [organizations :as org]
                     [subscriptions :as subs])
            (test.tree [script :refer [defgroup deftest]])
            [serializable.fn :refer [fn]]
            [test.assert :as assert]))

(defgroup distributor-tests

  (deftest "+New Distributor button is disabled for an organization with no environments"
    (with-unique [org-name "auto-org"]
      (api/create-organization org-name)
      (org/switch org-name)
      (assert/is (subs/new-distributor-button-disabled?)))))
