(ns katello.tests.distributors
  (:refer-clojure :exclude [fn])
  (:require (katello [api-tasks :as api]
                     [organizations :as org]
                     [client :as client]
                     [ui-common :as common]
                     [tasks :refer :all]
                     [subscriptions :as subs]
                     [validation :as val]
                     [fake-content  :as fake])
            [katello.tests.organizations :as org-tests]
            (test.tree [script :refer [defgroup deftest]]
                       [builder :refer [union]])
            [serializable.fn :refer [fn]]
            [test.assert :as assert]
            [bugzilla.checker :refer [open-bz-bugs]]))

(defgroup distributor-tests

  (deftest "+New Distributor button is disabled for an organization with no environments"
    (with-unique [org-name "auto-org"]
      (api/create-organization org-name)
      (org/switch org-name)
      (assert/is (subs/new-distributor-button-disabled?)))))
