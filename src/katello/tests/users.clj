(ns katello.tests.users

  (:use test.tree.script
        katello.validation
        [katello.organizations :only [create-organization delete-organization]]
        [katello.roles :only [assign-role]]
        katello.users
        katello.tasks
        [katello.ui-tasks :only [errtype]]
        [katello.conf :only [config]]
        [bugzilla.checker :only [open-bz-bugs]]))

;;; Variables


(def generic-user-details {:password "password", :email "blah@blah.com"})


;;; Tests

(defgroup user-tests

  (deftest "Admin creates a user"
    (create-user       (uniqueify "autouser")   generic-user-details)


    (deftest "Admin creates a user with a default organization"
      :blockers (open-bz-bugs "852119")
      
      (with-unique [org-name "auto-org"
                    env-name "environment"
                    username "autouser"]
        (create-organization     org-name  {:initial-env-name env-name})
        (create-user    username (merge generic-user-details {:default-org org-name, :default-env env-name}))))

    (deftest "Admin changes a user's password"
      :blockers (open-bz-bugs "720469")

      (with-unique [username "edituser"]
        (create-user    username                generic-user-details)
        (edit-user      username                {:new-password "changedpwd"})))


    (deftest "Admin deletes a user"
      (with-unique [username "deleteme"]
        (create-user    username                generic-user-details)
        (delete-user    username))

      (deftest "Admin who deletes the original admin account can still do admin things"
        (let [admin (@config :admin-user)
              pw    (@config :admin-password)]
          (try
            (delete-user admin)
            
            (with-unique [org "deleteme"]
              (create-organization org)
              (delete-organization org))
            (finally (create-user admin {:password pw
                                         :email "root@localhost"})
                     (assign-role {:user admin :roles ["Administrator"]}))))))


    (deftest "Two users with the same username is disallowed"
      :blockers (open-bz-bugs "738425")

      (with-unique [username "dupeuser"]
        (expecting-error-2nd-try (errtype :katello.notifications/name-taken-error)
          (create-user username generic-user-details))))


    (deftest "User's minimum password length is enforced"
      (expecting-error (errtype :katello.notifications/password-too-short)
                       (create-user (uniqueify "insecure-user") {:password "abcd", :email "me@my.org"})))


    (deftest "Admin assigns a role to user"
      (with-unique [username "autouser"]
        (create-user     username                generic-user-details)
        (assign-role     {:user username, :roles ["Administrator"]})))))
