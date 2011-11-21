(ns katello.tests.users
  (:refer-clojure :exclude [fn])
  (:use [test.tree.builder :only [fn]])
  (:require [katello.tasks :as tasks]
            [katello.validation :as v])
  (:use [slingshot.slingshot :only [try+ throw+]]))

(def details {:password "password" :email "blah@blah.com"})

(def create
  (fn [] (tasks/create-user (tasks/uniqueify "autouser") details)))

(def edit
  (fn [] (let [username (tasks/uniqueify "edituser")]
          (tasks/create-user username details)
          (tasks/edit-user username {:new-password "changedpwd"}))))

(def password-mismatch
  (fn [] (let [username (tasks/uniqueify "pwdmismatch")]
           (tasks/create-user username {:password "password"})
           (try+  (tasks/edit-user username {:new-password "password"
                                      :new-password-confirm "p@$$word"})
                  (throw+ {:type :no-mismatch-reported :msg "Passwords do not match not reported"})
                  (catch [:type :password-mismatch] _)))))

(def search-users
  "Search for users based on criteria."
  (fn [] (let [username (tasks/uniqueify "searchuser")]
           (tasks/create-user username {:password "password"
                                        :email "blah@blah.org"})
           (tasks/validate-search :users {:criteria "search"}))))

(def delete
  (fn [] (let [username (tasks/uniqueify "deleteme")]
          (tasks/create-user username details)
          (tasks/delete-user username))))

(def dupe-disallowed
  (fn [] (v/duplicate-disallowed
         tasks/create-user [(tasks/uniqueify "dupeuser") details])))

(def min-password-length
  (fn []
    (v/field-validation tasks/create-user [(tasks/uniqueify "insecure-user") {:password "abcd" :email "me@my.org"}]
                        (v/expect-error :password-too-short))))

(def assign-role
  (fn []
     (let [username (tasks/uniqueify "autouser")]
          (tasks/create-user username details)
          (tasks/assign-role {:user username
                              :roles ["Administrator"]}))))
