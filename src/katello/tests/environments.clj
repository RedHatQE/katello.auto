(ns katello.tests.environments
  (:refer-clojure :exclude [fn])
  (:require [katello.api-tasks :as api]
            )
  (:use katello.tasks
        katello.validation 
        [com.redhat.qe.verify :only [verify-that]]
        [serializable.fn :only [fn]]))

(def test-org-name (atom nil))
(def locker "Locker")
(def first-env "dev")

(def create-test-org 
  (fn []
    (api/with-admin-creds
      (api/create-organization
       (reset! test-org-name (uniqueify "env-test"))
       {:description "organization used to test environments."}))))

(def create
  (fn []
    (verify-success
     #(create-environment (uniqueify "simple-env")
                          {:org-name @test-org-name
                           :description "simple environment description"}))))

(def delete
  (fn []
    (let [env-name (uniqueify "delete-env")]
      (create-environment
       env-name
       {:org-name @test-org-name
        :description "simple environment description"})
      (verify-success
       #(delete-environment env-name {:org-name @test-org-name})))))

(def dupe-disallowed
  (fn [] (duplicate-disallowed create-environment
                              [(uniqueify "test-dup")
                               {:org-name @test-org-name
                                :description "dup env description"}]
                              (expect-error :name-must-be-unique-within-org))))

(def rename
  (fn []
    (let [env-name (uniqueify "rename")
          new-name (uniqueify "newname")]
      (create-environment env-name
                          {:org-name @test-org-name
                           :description "try to rename me!"})
      (edit-environment env-name {:org-name @test-org-name
                                  :new-name new-name})
      (navigate :named-environment-page
                {:org-name @test-org-name
                 :env-name new-name}))))

(def name-required
  (fn [] (name-field-required create-environment
                             [nil {:org-name @test-org-name
                                   :description "env description"}])))


