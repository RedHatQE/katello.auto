(ns katello.tests.users
  (:refer-clojure :exclude [fn])
  (:use [serializable.fn :only [fn]])
  (:require (katello [validation :as v]))
  (:use [katello.tasks :exclude [assign-role]]
        [slingshot.slingshot :only [try+ throw+]]))

(def details {:password "password" :email "blah@blah.com"})

(def create
  (fn [] (create-user (uniqueify "autouser") details)))

(def edit
  (fn [] (let [username (uniqueify "edituser")]
          (create-user username details)
          (edit-user username {:new-password "changedpwd"}))))

(def password-mismatch
  (fn [] (let [username (uniqueify "pwdmismatch")]
          (create-user username {:password "password"})
          (try+  (edit-user username {:new-password "password"
                                      :new-password-confirm "p@$$word"})
                 (throw+ {:type :no-mismatch-reported :msg "Passwords do not match not reported"})
                 (catch [:type :password-mismatch] _)))))

(def search-users
  "Search for users based on criteria."
  (fn [] (let [username (uniqueify "searchuser")]
          (create-user username {:password "password"
                                 :email "blah@blah.org"})
          (validate-search :users {:criteria "search"}))))

(def delete
  (fn [] (let [username (uniqueify "deleteme")]
          (create-user username details)
          (delete-user username))))

(def dupe-disallowed
  (fn [] (v/duplicate-disallowed
         create-user [(uniqueify "dupeuser") details])))

(def min-password-length
  (fn []
    (v/field-validation create-user [(uniqueify "insecure-user") {:password "abcd" :email "me@my.org"}]
                        (v/expect-error :password-too-short))))

(def assign-role
  (fn []
    (let [username (uniqueify "autouser")]
      (create-user username details)
      (katello.tasks/assign-role {:user username
                                  :roles ["Administrator"]}))))
