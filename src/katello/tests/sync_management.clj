(ns katello.tests.sync_management
  (:require [katello :as kt]
            (katello [navigation :as nav]
                     [ui :as ui]
                     [rest :as rest]
                     [validation :as validate]
                     [login :refer [login]]
                     [repositories :as repo]
                     [tasks :refer :all]
                     [ui-common :as common]
                     [sync-management :as sync]
                     [changesets :as changeset]
                     [conf :refer [config *environments* *session-org* *session-user*]]
                     [blockers :refer [bz-bugs]])
            [katello.tests.useful :as testfns]
            [test.tree.script :refer :all]
            [test.assert :as assert]))


;; Functions

(defmacro fresh-repo []
  `(testfns/fresh-repo *session-org* (@config :sync-repo)))

(defn complete? [sync-result]
  (= "Sync complete." sync-result))

(defn plan-validate [plan expected]
  (expecting-error (common/errtype expected)
                   (ui/create plan)))

(defn plan-validation-data []
  [[(kt/newSyncPlan {:start-time (java.util.Date.) :interval "daily"}) :katello.notifications/name-cant-be-blank]
   (with-meta
     [(kt/newSyncPlan {:name "blah" :start-time-literal "" :start-date-literal ""}) :katello.notifications/start-date-time-cant-be-blank]
     {:blockers (bz-bugs "853229")})])

(defn create-all-and-sync
  "Creates all the given repos, including their products and
  providers.  All must not exist already."
  [repos]
  (testfns/create-all-recursive repos)
  (sync/perform-sync (filter (complement :unsyncable) repos)))

(with-unique-ent "plan" (kt/newSyncPlan {:name "syncplan", :description "mydescription", :interval "daily",
                                         :org *session-org*}))
;; Tests

(defgroup sync-tests

  (deftest "Sync a small repo"
    (->> (fresh-repo) list create-all-and-sync vals (every? complete?) assert/is))

  (deftest "Create a sync plan"
    :blockers (bz-bugs "729364")
    (with-unique-plan p
      (ui/create p))

    (deftest "Change interval of an existing sync plan"
      (with-unique-plan p
        (ui/create p)
        (ui/update p assoc :interval "Weekly")))

    (deftest "Rename an existing sync plan"
      (with-unique-plan p
        (ui/create p)
        (let [newp (ui/update p update-in [:name] str "-renamed")]
          (nav/go-to newp))))

    (deftest "Sync plan validation"
      :data-driven true

      plan-validate
      [(fn [] [(kt/newSyncPlan {:start-time (java.util.Date.) :interval "daily", :org *session-org*}) :katello.notifications/name-cant-be-blank])
       (with-meta
         (fn [] [(kt/newSyncPlan {:name "blah" :start-time-literal "" :start-date-literal "", :org *session-org*}) :katello.notifications/start-date-time-cant-be-blank])
         {:blockers (bz-bugs "853229")})])

    (deftest "Cannot create two sync plans with the same name"
      (with-unique-plan p
        (validate/expecting-error-2nd-try validate/duplicate-disallowed
                                          (ui/create p))))

    (deftest "Assign a sync plan to multiple products"
      :blockers (bz-bugs "751876" "965200")
      (with-unique-plan p
        (let [prov (uniqueify (kt/newProvider {:name "multiplan", :org *session-org*}))
              repos (for [repo (take 3 (repeatedly #(fresh-repo)))]
                      (update-in repo [:product] assoc :provider prov))
              prods (map :product repos)]
          (rest/create-all (conj (concat prods repos) prov))
          (changeset/api-promote (first *environments*) prods)
          (ui/create p)
          (sync/schedule {:products prods, :plan p})
          (let [expected-plan (:name p)
                actual-plans (vals (sync/current-plan prods))]
            (assert/is (every? (partial = expected-plan) actual-plans)))))

      (deftest "Re-assign a different sync plan to a product"
        (with-unique-plan [p1 p2]
          (let [repo (fresh-repo)
                prod (:product repo)
                prods (list prod)]
            (ui/create-all (list p1 p2 (:provider prod) prod repo))
            (sync/schedule {:products prods :plan p1})
            (sync/schedule {:products prods :plan p2})
            (assert/is (= ((sync/current-plan prods) prod)
                          (:name p2)))))))))
