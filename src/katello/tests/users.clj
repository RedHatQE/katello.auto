(ns katello.tests.users
  (:refer-clojure :exclude [fn])
  (:use [test.tree :only [fn]])
  (:require [katello.tasks :as tasks]
            [katello.validation :as v]))

(def create
  (fn [] (tasks/create-user (tasks/uniqueify "autouser")
                           {:password "password"})))

(def edit
  (fn [] (let [username (tasks/uniqueify "autouser")]
          (tasks/create-user username {:password "password"})
          (tasks/edit-user username {:new-password "changedpw"}))))

(def delete
  (fn [] (let [username (tasks/uniqueify "deleteme")]
          (tasks/create-user username {:password "mypassword"})
          (tasks/delete-user username))))

(def dupe-disallowed
  (fn [] (v/duplicate-disallowed
         tasks/create-user [(tasks/uniqueify "dupeuser") {:password "mypassword"}])))

(def min-password-length
  (fn []
    (v/field-validation tasks/create-user [(tasks/uniqueify "insecure-user") {:password "asdf"}]
                        (v/expect-error :password-too-short))))

(def assign-role
  (fn []
     (let [username (tasks/uniqueify "autouser")]
          (tasks/create-user username {:password "password"})
          (tasks/assign-role {:user username
                              :roles ["Administrator"]}))))
