(ns katello.tests.promotions
  (:require [katello :as kt]
            (katello [rest :as rest]
                     [ui :as ui]
                     [sync-management :as sync]
                     [environments :as env]
                     providers
                     repositories
                     [tasks :refer [with-unique uniques uniqueify]] 
                     [fake-content :as fake]
                     [changesets :as changeset]
                     [sync-management :as sync]
                     [conf :refer [config *session-org*]]
                     [blockers :refer [bz-bugs]])
            [katello.tests.useful :refer [create-recursive] :as testfns]
            (test.tree [script :refer :all]
                       [builder :refer [data-driven dep-chain]])
            [serializable.fn :refer [fn]]
            [test.assert :as assert]
            [clojure.set :refer [index]])
  (:refer-clojure :exclude [fn]))

(defmacro fresh-repo []
  `(testfns/fresh-repo *session-org* (@config :sync-repo)))

(def promo-data
  (runtime-data [2 (list (:product (fresh-repo)))]))

#_(defn verify-promote-content [num-envs content-to-promote]
  ;;create envs
  (let [envs (->> {:name "promo-env", :org *session-org*}
                  kt/newEnvironment
                  uniques
                  (take num-envs)
                  kt/chain)
        setup-item {katello.Product (fn [prod] ; create a repo in the given product and sync it
                                      (let [repo (assoc (fresh-repo) :product prod)]
                                        (create-recursive repo)
                                        (sync/perform-sync (list repo))))}]
    (rest/create-all envs)
    (doseq [item content-to-promote]
      ((setup-item (class item)) item))

    (doseq [target-env envs]
      (changeset/promote-delete-content (uniqueify (kt/newChangeset {:name "cs", :env target-env
                                                                     :content content-to-promote}))))
    (let [ui-member (fn [s m] ; check that m's name and class match an item in the set.
                      (some (fn [i]
                              (let [l (list i m)]
                                (and (map (comp = class) l)
                                     (map (comp = :name) l))))
                            s))]
      (assert/is (every? (partial ui-member (changeset/environment-content (last envs)))
                         (set content-to-promote))))))

#_(defgroup promotion-tests
  
  :blockers (bz-bugs "714297" "738054" "745315" "784853" "845096" "955729")
          
  (deftest "Promote content"
    :uuid "fa2796db-f82f-f564-aa13-8239f154154d"
    :data-driven true
    :description "Takes content and promotes it thru more
                   environments. Verifies that it shows up in the new
                   env."
    verify-promote-content
    promo-data))
