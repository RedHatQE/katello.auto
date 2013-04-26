(ns katello.tests.navigation
  (:require [test.tree.script :refer :all] 
            (katello [navigation :as nav]
                     [conf :refer [*session-org*]]
                     [notifications :refer [verify-no-error]]
                     [rest :refer [katello-only]])))

;; Constants

(def common-tabs '(:katello.roles/page
                     :katello.users/page 
                     :katello.systems/page
                     :katello.activation-keys/page
                     :katello.systems/by-environments-page))

(def ^{:doc "Tabs that don't exist in headpin"}
  katello-only-tabs
  '(:katello.repositories/redhat-page))

;;; Functions

(defn verify-navigation
  "Navigates to a page"
  [page]
  (nav/go-to page *session-org*)
  (verify-no-error {:timeout-ms 2000}))

(def all-navigation-tabs
  (concat (map vector common-tabs)
          (for [t katello-only-tabs]
            (with-meta (vector t) {:blockers katello-only}))))


;;; Tests

(defgroup nav-tests

  (deftest "Navigate to tab" 
    :data-driven true
    verify-navigation
 
    all-navigation-tabs))

