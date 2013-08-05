(ns katello.tests.promotions
  (:require [katello :as kt]
            (katello [rest :as rest]
                     [ui :as ui]
                     [sync-management :as sync]
                     [environments :as env]
                     providers
                     repositories
                     [tasks :refer [with-unique uniques uniqueify]] 
                     [content-view-definitions :as views]
                     [fake-content :as fake]
                     [changesets :as changeset]
                     [sync-management :as sync]
                     [conf :refer [config *session-org* ]]
                     [blockers :refer [bz-bugs]])
            [katello.tests.useful :refer [create-recursive fresh-repo]]
            [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            (test.tree [script :refer :all]
                       [builder :refer [data-driven dep-chain]])
            [serializable.fn :refer [fn]]
            [test.assert :as assert]
            [clojure.set :refer [index]])
  (:refer-clojure :exclude [fn]))

(defgroup promotion-tests        
  (deftest "Promote content"
    :uuid "fa2796db-f82f-f564-aa13-8239f154154d"
    :description "Takes content and promotes it thru more environments. 
                  Verifies that it shows up in the new env."
    (with-unique [org        (kt/newOrganization {:name "org"})
                  env (kt/newEnvironment {:name "env" :org org})
                  cv         (kt/newContentView {:name "content-view"
                                                 :org org
                                                 :published-name "publish-name"})             
                  cs         (kt/newChangeset {:name "cs"
                                               :env env
                                               :content (list cv)})]
        (let [repo (fresh-repo org (@config :sync-repo))]
          (ui/create-all (list org env cv))
          (create-recursive repo)
          (sync/perform-sync (list repo))
          (ui/update cv assoc :products (list (kt/product repo)))
          (views/publish {:content-defn cv
                          :published-name (:published-name cv)
                          :description "test pub"
                          :org org})
          (changeset/promote-delete-content cs)
          (assert/is (changeset/environment-has-content? cs))))))
              
