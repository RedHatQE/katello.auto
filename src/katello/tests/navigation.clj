(ns katello.tests.navigation
  (:require [test.tree.script :refer :all] 
            (katello [notifications :refer [check-for-error]]
                     [ui-tasks :refer :all]
                     [locators :refer [tab-list]])))

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

