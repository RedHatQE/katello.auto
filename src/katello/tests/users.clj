(ns katello.tests.users
  (:refer-clojure :exclude [fn])
  (:use [serializable.fn :only [fn]]
        katello.validation
        test.tree.script
        katello.tasks))

;;; Variables


(def user-details {:password "password", :email "blah@blah.com"})


;;; Tests

(def all-user-tests

  (deftest "create a user"
    (create-user       (uniqueify "autouser")   user-details)


    (deftest "edit a user"
      (with-unique username "edituser"
        (create-user    username                user-details)
        (edit-user      username                {:new-password "changedpwd"})))

  
    (deftest "delete a user"
      (with-unique username "deleteme"
        (create-user    username                user-details)
        (delete-user    username)))


    (deftest "duplicate user disallowed"
      (verify-2nd-try-fails-with       :name-taken-error     create-user    (uniqueify "dupeuser")    user-details))


    (deftest "users' miniumum password length enforced"
      (expect-error-on-action :password-too-short  create-user (uniqueify "insecure-user") {:password "abcd", :email "me@my.org"}))

  
    (deftest "search for user"
      (with-unique username "searchuser"
        (create-user     username                {:password "password", :email "blah@blah.org"})
        (validate-search :users                  {:criteria "search"})))

  
    (deftest "assign role to user"
      (with-unique username "autouser"
        (create-user     username                user-details)
        (assign-role     {:user username, :roles ["Administrator"]})))))




