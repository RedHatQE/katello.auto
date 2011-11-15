(ns katello.tests.users
  (:refer-clojure :exclude [fn])
  (:use [test.tree.builder :only [fn]])
  (:require [katello.tasks :as tasks]
            [katello.validation :as v]))

(def details {:password "password" :email "blah@blah.com"})

(def create
  (fn [] (tasks/create-user (tasks/uniqueify "autouser") details)))

(def edit
  (fn [] (let [username (tasks/uniqueify "autouser")]
          (tasks/create-user username details)
          (tasks/edit-user username {:new-password "changedpw"}))))

(def delete
  (fn [] (let [username (tasks/uniqueify "deleteme")]
          (tasks/create-user username details)
          (tasks/delete-user username))))

(def dupe-disallowed
  (fn [] (v/duplicate-disallowed
         tasks/create-user [(tasks/uniqueify "dupeuser") details])))

(def min-password-length
  (fn []
    (v/field-validation tasks/create-user [(tasks/uniqueify "insecure-user") details]
                        (v/expect-error :password-too-short))))

(def assign-role
  (fn []
     (let [username (tasks/uniqueify "autouser")]
          (tasks/create-user username details)
          (tasks/assign-role {:user username
                              :roles ["Administrator"]}))))
