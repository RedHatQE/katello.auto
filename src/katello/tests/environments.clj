(ns katello.tests.environments
  (:refer-clojure :exclude [fn])
  (:use [test.tree.builder :only [fn]]
        [com.redhat.qe.verify :only [verify-that]])
  (:require [katello.tasks :as tasks]
            [katello.api-tasks :as api]
            [katello.validation :as validate]))

(def test-org-name (atom nil))
(def locker "Locker")
(def first-env "dev")

(def create-test-org 
  (fn []
    (api/with-admin-creds
      (api/create-organization
       (reset! test-org-name (tasks/uniqueify "env-test"))
       {:description "organization used to test environments."}))))

(def create
  (fn []
    (tasks/verify-success
     #(tasks/create-environment (tasks/uniqueify "simple-env")
                                {:org-name @test-org-name
                                 :description "simple environment description"}))))

(def delete
  (fn []
    (let [env-name (tasks/uniqueify "delete-env")]
      (tasks/create-environment
       env-name
       {:org-name @test-org-name
        :description "simple environment description"})
      (tasks/verify-success
       #(tasks/delete-environment env-name {:org-name @test-org-name})))))

(def dupe-disallowed
  (fn [] 
    (validate/duplicate-disallowed tasks/create-environment
                                   [(tasks/uniqueify "test-dup") {:org-name @test-org-name
                                                                  :description "dup env description"}]
                                   (validate/expect-error :name-must-be-unique-within-org))))

(def rename
  (fn []
    (let [env-name (tasks/uniqueify "rename")
          new-name (tasks/uniqueify "newname")]
      (tasks/create-environment env-name
                                {:org-name @test-org-name
                                 :description "try to rename me!"})
      (tasks/edit-environment env-name {:org-name @test-org-name
                                        :new-name new-name})
      (tasks/navigate :named-environment-page
                      {:org-name @test-org-name
                       :env-name new-name}))))

(def name-required
  (fn [] (validate/name-field-required tasks/create-environment
                                      [nil {:org-name @test-org-name
                                            :description "env description"}])))


