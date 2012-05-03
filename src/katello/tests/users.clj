(ns katello.tests.users
  
  (:use test.tree.script
        katello.validation
        katello.tasks))

;;; Variables


(def user-details {:password "password", :email "blah@blah.com"})


;;; Tests

(defgroup all-user-tests
  
  (deftest "Admin creates a user"
    (create-user       (uniqueify "autouser")   user-details)


    (deftest "Admin changes a user's password"
      (with-unique username "edituser"
        (create-user    username                user-details)
        (edit-user      username                {:new-password "changedpwd"})))

  
    (deftest "Admin deletes a user"
      (with-unique username "deleteme"
        (create-user    username                user-details)
        (delete-user    username)))


    (deftest "Two users with the same username is disallowed"
      (verify-2nd-try-fails-with       :name-taken-error     create-user    (uniqueify "dupeuser")    user-details))


    (deftest "User's minimum password length is enforced"
      (expect-error-on-action :password-too-short  create-user (uniqueify "insecure-user") {:password "abcd", :email "me@my.org"}))

  
    (deftest "Search for a user"
      (with-unique username "searchuser"
        (create-user     username                user-details)
        (validate-search :users                  {:criteria "search"})))

  
    (deftest "Admin assigns a role to user"
      (with-unique username "autouser"
        (create-user     username                user-details)
        (assign-role     {:user username, :roles ["Administrator"]})))))
