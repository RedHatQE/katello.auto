(ns katello.tests.users

  (:require (katello [validation :refer :all] 
                     [organizations :as organization] 
                     [roles :as role] 
                     [users :as user]
                     [tasks :refer :all]
                     [ui-tasks :refer [errtype]] 
                     [conf :refer [config]]) 
            [test.tree.script :refer :all] 
            [bugzilla.checker :refer [open-bz-bugs]]))

;;; Variables


(def generic-user-details {:password "password", :email "blah@blah.com"})


;;; Tests

(defgroup user-tests

  (deftest "Admin creates a user"
    (user/create       (uniqueify "autouser")   generic-user-details)


    (deftest "Admin creates a user with a default organization"
      :blockers (open-bz-bugs "852119")
      
      (with-unique [org-name "auto-org"
                    env-name "environment"
                    username "autouser"]
        (organization/create     org-name  {:initial-env-name env-name})
        (user/create    username (merge generic-user-details {:default-org org-name, :default-env env-name}))))

    (deftest "Admin changes a user's password"
      :blockers (open-bz-bugs "720469")

      (with-unique [username "edituser"]
        (user/create    username                generic-user-details)
        (user/edit      username                {:new-password "changedpwd"})))


    (deftest "Admin deletes a user"
      (with-unique [username "deleteme"]
        (user/create    username                generic-user-details)
        (user/delete    username))

      (deftest "Admin who deletes the original admin account can still do admin things"
        (let [admin (@config :admin-user)
              pw    (@config :admin-password)]
          (try
            (user/delete admin)
            
            (with-unique [org "deleteme"]
              (organization/create org)
              (organization/delete org))
            (finally (user/create admin {:password pw
                                         :email "root@localhost"})
                     (role/assign {:user admin :roles ["Administrator"]}))))))


    (deftest "Two users with the same username is disallowed"
      :blockers (open-bz-bugs "738425")

      (with-unique [username "dupeuser"]
        (expecting-error-2nd-try (errtype :katello.notifications/name-taken-error)
          (user/create username generic-user-details))))


    (deftest "User's minimum password length is enforced"
      (expecting-error (errtype :katello.notifications/password-too-short)
                       (user/create (uniqueify "insecure-user") {:password "abcd", :email "me@my.org"})))


    (deftest "Admin assigns a role to user"
      (with-unique [username "autouser"]
        (user/create     username                generic-user-details)
        (role/assign     {:user username, :roles ["Administrator"]})))))
