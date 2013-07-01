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
                     :katello.activation-keys/page))

(def ^{:doc "Tabs that don't exist in headpin"}
  katello-only-tabs
  '(:katello.repositories/redhat-page
    :katello.systems/by-environments-page
    :katello.gpg-keys/page))

;;; Functions

(defn verify-navigation
  "Navigates to a page"
  [page]
  (nav/go-to page *session-org*)
  (verify-no-error {:timeout-ms 2000}))

(def all-navigation-tabs
  (concat (map vector common-tabs)
          (for [t katello-only-tabs]
            (with-meta (vector t) {:blockers (list katello-only)}))))


;;; Tests

(defgroup nav-tests

  (deftest "Navigate to tab"
    :uuid "ce9c80d9-5323-9104-8e63-90d062d28b9b"
    :data-driven true
    verify-navigation
 
    all-navigation-tabs))

