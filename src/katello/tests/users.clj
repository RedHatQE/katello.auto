(ns katello.tests.users
  (:require katello
            (katello [validation :refer :all] 
                     [organizations :as organization]
                     [ui :as ui]
                     [navigation :as nav]
                     [rest :as rest]
                     [login :refer [login logout]]
                     [ui-common :as common]
                     [roles :as role] 
                     [users :as user]
                     [tasks :refer :all] 
                     [content-search :refer [list-available-orgs]]
                     [conf :refer [config]]
                     [api-tasks :as api]) 
            [test.tree.script :refer :all]
            [test.assert :as assert]
            [clojure.string :refer [capitalize upper-case lower-case]]
            [bugzilla.checker :refer [open-bz-bugs]]))

;;; Constants

(def generic-user (katello/newUser {:password "password", :email "blah@blah.com"}))

;;; Functions

(defn new-unique-user []
  (with-unique [org (katello/newOrganization {:name "org"})
                user (assoc generic-user :name "user" :default-org org)]
    user))

(defn create-org-and-user
  [{:keys [default-org] :as user}]
  (rest/create-all (list user default-org))
  (ui/update user assoc :roles (hash-set (katello/newRole {:name "Administrator"}))))

(defn set-default-org-at-login-screen
  [{:keys [default-org] :as user}]
  (login user  {:default-org default-org
                :org default-org})
  user) ;; return user for more steps

(defn logout-user [user]
  (logout)
  user)

(defn verify-login-direct-to-default-org
  [{:keys [default-org] :as user}]
  (login user)
  (assert/is (= (nav/current-org) (:name default-org))))



(defn verify-login-prompts-org [user]
  (expecting-error [:type :katello.login/login-org-required]
                   (login user)))

(defn verify-only-one-org [_]
  (assert/is (= (list-available-orgs) 
                (nav/current-org))))

(defn verify-multiple-orgs [_]
  (assert/is (< 1 (count (list-available-orgs)))))
  
(defn set-default-org [{:keys [default-org] :as user}]
  (organization/switch nil {:default-org default-org})
  user)


;;; Tests

(defgroup default-org-tests
  :test-teardown #(login)
  (deftest "Set default org for a user at login"
    (-> (new-unique-user)
        create-org-and-user
        set-default-org-at-login-screen
        logout-user
        verify-login-direct-to-default-org)
     

    (deftest "Unset default org for a user at login"
      (do-steps (uniqueify-vals {:username "deforg"
                                 :org "usersorg"})
                create-org-and-user
                set-default-org-at-login-screen
                step-unset-default-org
                logout-user
                verify-login-prompts-org)))
  
    (deftest "Default Org - user can change default org (smoke test)"
      (do-steps(merge (uniqueify-vals 
                        {:username "deforg"
                         :org "usersorg"})
                        {:new-org "ACME_Corporation"})
                create-org-and-user
                set-default-org-at-login-screen
                logout-user
                verify-login-direct-to-default-org
                set-default-org
                step-verify-login-direct-to-new-default-org 
                ))
    
    (deftest "Default Org - user w/o rights cannot change default org (smoke test)"
      (let [user (uniqueify "deforg")
               org  (uniqueify "usersorg")]
           (do-steps {:username user
                      :org org
                      :roles []
                      :default-org "ACME_Corporation"
                      :default-env nil}
                   create-org-and-user
                   set-default-org-at-login-screen
                   verify-only-one-org
                   ))))


(defgroup user-tests

  (deftest "Admin creates a user"
    (user/create (uniqueify "autouser")   generic-user)
    
    (deftest "Admin creates a user with i18n characters"
      :data-driven true
      :blockers (open-bz-bugs "868906")
      
      (fn [username]
        (user/create (uniqueify username)   generic-user))
      [["صالح"] ["Гесер"] ["洪"]["標準語"]])

    (deftest "User validation"
      :data-driven true

      (fn [username expected-err]
        (expecting-error (common/errtype expected-err)
                         (user/create username generic-user)))
      [[(random-string (int \a) (int \z) 2) :katello.notifications/username-must-contain-3-char]
       [(random-string (int \a) (int \z) 65) :katello.notifications/username-64-char-limit]
       ["foo   " :katello.notifications/validation-error]
       ["   foo   " :katello.notifications/validation-error]
       ["<a href='foo'>Click Here></a>" :katello.notifications/validation-error]
       ["#$%^" :katello.notifications/validation-error]
       ["" :katello.notifications/username-cant-be-blank]])

    (deftest "Admin creates a user with a default organization"
      :blockers (open-bz-bugs "852119")
      
      (with-unique [org (organization/new- {:name "auto-org"})
                    env-name "environment"
                    username "autouser"]
        (ui/create (assoc org {:initial-env-name env-name}))
        
        (user/create username (merge generic-user {:default-org org-name, :default-env env-name}))))

    (deftest "Admin changes a user's password"
      :blockers (open-bz-bugs "720469")

      (with-unique [username "edituser"]
        (user/create username generic-user)
        (user/edit username {:new-password "changedpwd"})))


    (deftest "Admin deletes a user"
      (with-unique [username "deleteme"]
        (user/create username generic-user)
        (user/delete username))

      (deftest "Admin who deletes the original admin account can still do admin things"
        :blockers (open-bz-bugs "868910")
        
        (let [admin (@config :admin-user)
              pw (@config :admin-password)]
          (try
            (user/delete admin)
            
            (with-unique [org "deleteme"]
              (organization/create org)
              (organization/delete org))
            (finally (user/create admin {:password pw
                                         :email "root@localhost"})
                     (user/assign {:user admin :roles ["Administrator"]}))))))

    (deftest "Two users with the same username is disallowed"
      :blockers (open-bz-bugs "738425")

      (with-unique [username "dupeuser"]
        (expecting-error-2nd-try (common/errtype :katello.notifications/name-taken-error)
                                 (user/create username generic-user))))
    
    (deftest "Two users with username that differs only in case are allowed (like unix)"
      :blockers (open-bz-bugs "857876")
      :data-driven true
      (fn [orig-name modify-case-fn]
        (with-unique [name orig-name]
          (user/create name generic-user)
          (user/create (modify-case-fn name) generic-user)))

      [["usr"     capitalize]
       ["yourusr" capitalize]
       ["usr"     upper-case]
       ["MyUsr"   upper-case]
       ["YOURUsr" lower-case]])
    
    (deftest "Delete user-notifications"
      :data-driven true
      
      (fn [delete-all?]
        (with-unique [username "autouser"]
          (let [password "abcd1234"]
            (user/create username {:password password :email "me@my.org"})
            (user/assign {:user username, :roles ["Administrator"]})
            (logout)
            (login username password (@config :admin-org))
            (user/delete-notifications delete-all?))))
      
      [[true]
       [false]])
    
    
    (deftest "User's minimum password length is enforced"
      (expecting-error (common/errtype :katello.notifications/password-too-short)
                       (user/create (uniqueify "insecure-user") {:password "abcd", :email "me@my.org"})))


    (deftest "Admin assigns a role to user"
      (with-unique [username "autouser"]
        (user/create username generic-user)
        (user/assign {:user username, :roles ["Administrator"]})))
  
    (deftest "Roles can be removed from user"
      (with-unique [username "autouser"]
        (user/create username generic-user)
        (user/assign {:user username, :roles ["Administrator" "Read Everything"]})
        (user/unassign {:user username, :roles ["Read Everything"]}))))

  default-org-tests)
