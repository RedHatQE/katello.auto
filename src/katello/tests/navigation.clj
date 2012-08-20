(ns katello.tests.navigation
  (:use test.tree.script
        katello.ui-tasks
        [katello.locators :only [tab-list]] ))

;;; Functions

(defn verify-navigation
  "Navigates to a tab"
  [tab]
  (navigate tab)
  (check-for-error {:timeout-ms 2000}))

(def all-navigation-tabs
  (map vector tab-list))


;;; Tests

(defgroup nav-tests

  (deftest "Navigate to tab" 
    :data-driven true
    verify-navigation
 
    all-navigation-tabs))

