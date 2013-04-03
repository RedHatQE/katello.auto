(ns katello.tests.templates
  (:refer-clojure :exclude [fn])
  (:require [test.tree.script :refer :all] 
            [serializable.fn :refer [fn]]
            [bugzilla.checker :refer [open-bz-bugs]]
            [katello :as kt]
            (katello [api-tasks :as api] 
                     [tasks :refer :all] 
                     system-templates
                     [changesets :as changeset]
                     [sync-management :as sync]
                     [ui :as ui]
                     [conf :refer [*environments* *session-org* config]])
            [katello.tests.useful :as testfns]))

;; Variables

;; Functions

(defmacro fresh-repo []
  `(testfns/fresh-repo *session-org* (@config :sync-repo)))

;; Tests

(defgroup template-tests
  :blockers (open-bz-bugs "765888")
  
  (deftest "Create a system template" 
    (-> {:name "template" :org *session-org*}
        kt/newTemplate uniqueify ui/create)

    (deftest "Add custom content to a system template"
      (with-unique [t (kt/newTemplate {:name "templ", :org *session-org*})]
        (ui/create t)
        (let [repo (fresh-repo)]
          (testfns/create-recursive repo)
          (sync/perform-sync (list repo))
          (changeset/api-promote (first *environments*) (list (:product repo)))
          (ui/create t)
          (ui/update t assoc :content (list repo)))))))
