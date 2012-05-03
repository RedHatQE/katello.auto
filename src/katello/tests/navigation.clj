(ns katello.tests.navigation
  (:use test.tree.script
        katello.tasks
        [katello.locators :only [tab-list]] ))

;;; Keywords

(defn verify-navigation
  "Navigates to a tab"
  [tab]
  (navigate tab)
  (check-for-error 2000))

(def all-navigation-tabs
  (map vector tab-list))

;;; Tests


(defgroup navtests

  (deftest :data-driven 
    "navigate to tab"
    verify-navigation
 
    all-navigation-tabs))

