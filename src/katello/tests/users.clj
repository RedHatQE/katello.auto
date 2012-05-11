(ns katello.tests.users
  
  (:use test.tree.script
        katello.validation
        katello.tasks
        katello.ui-tasks
        [bugzilla.checker :only [open-bz-bugs]]))

;;; Variables


(def generic-user-details {:password "password", :email "blah@blah.com"})


;;; Tests

(defgroup user-tests
  
  (deftest "Admin creates a user"
    (create-user       (uniqueify "autouser")   generic-user-details)


    (deftest "Admin changes a user's password"
      :blockers (open-bz-bugs "720469")
      
      (with-unique [username "edituser"]
        (create-user    username                generic-user-details)
        (edit-user      username                {:new-password "changedpwd"})))

  
    (deftest "Admin deletes a user"
      (with-unique [username "deleteme"]
        (create-user    username                generic-user-details)
        (delete-user    username)))


    (deftest "Two users with the same username is disallowed"
      :blockers (open-bz-bugs "738425")

      (verify-2nd-try-fails-with       :name-taken-error     create-user    (uniqueify "dupeuser")    generic-user-details))


    (deftest "User's minimum password length is enforced"
      (expect-error-on-action :password-too-short  create-user (uniqueify "insecure-user") {:password "abcd", :email "me@my.org"}))

  
    (deftest "Search for a user"
      (with-unique [username "mybazquuxuser"]
        (create-user                                username                generic-user-details)
        (verify-all-search-results-contain-criteria :users                  {:criteria "mybazquux"})))

  
    (deftest "Admin assigns a role to user"
      (with-unique [username "autouser"]
        (create-user     username                generic-user-details)
        (assign-role     {:user username, :roles ["Administrator"]})))))
