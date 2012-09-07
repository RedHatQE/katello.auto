(ns katello.tests.navigation
  (:require [test.tree.script :refer :all] 
            (katello [notifications :refer [check-for-error]]
                     [ui-tasks :refer :all]
                     [api-tasks :refer [katello-only]]
                     [locators :refer [tab-list katello-only-tabs]])))

;;; Functions

(defn verify-navigation
  "Navigates to a tab"
  [tab]
  (navigate tab)
  (check-for-error {:timeout-ms 2000}))

(def all-navigation-tabs
  (concat (map vector tab-list)
          (for [t katello-only-tabs]
            (with-meta (vector t) {:blockers katello-only}))))


;;; Tests

(defgroup nav-tests

  (deftest "Navigate to tab" 
    :data-driven true
    verify-navigation
 
    all-navigation-tabs))

