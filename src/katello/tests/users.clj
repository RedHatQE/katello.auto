(ns katello.tests.users
  (:require (katello [validation :refer :all] 
                     [organizations :as organization]
                     [ui :as ui]
                     [login :refer [login]]
                     [ui-common :as common]
                     [roles :as role] 
                     [users :as user]
                     [tasks :refer :all] 
                     [conf :refer [config]]
                     [api-tasks :as api]) 
            [test.tree.script :refer :all]
            [test.assert :as assert]
            [clojure.string :refer [capitalize upper-case lower-case]]
            [bugzilla.checker :refer [open-bz-bugs]]))

;;; Constants

(def generic-user-details {:password "password", :email "blah@blah.com"})

;;; Functions

(defn step-create-org-and-user [{:keys [username org]}]
  (api/create-user username generic-user-details)
  (api/create-organization org)
  (user/assign {:user username :roles ["Administrator"]}))

(defn step-set-default-org-at-login-screen [{:keys [username org]}]
  (login username (:password generic-user-details) {:default-org org
                                                         :org org}))

(defn step-logout [_]
  (common/logout))

(defn step-verify-login-direct-to-default-org [{:keys [username org]}]
  (login username (:password generic-user-details))
  (assert/is (= (organization/current)
                org)))

(defn step-verify-login-prompts-org [{:keys [username org]}]
  (expecting-error [:type :katello.users/login-org-required]
                   (login username (:password generic-user-details))))

(defn step-unset-default-org [_]
  (organization/switch nil {:default-org :none}))

;;; Tests

(defgroup default-org-tests
  :test-teardown #(login)
  (deftest "Set default org for a user at login"
    (do-steps (uniqueify-vals {:username "deforg"
                               :org "usersorg"})
              step-create-org-and-user
              step-set-default-org-at-login-screen
              step-logout
              step-verify-login-direct-to-default-org) 

    (deftest "Unset default org for a user at login"
      (do-steps (uniqueify-vals {:username "deforg"
                                 :org "usersorg"})
                step-create-org-and-user
                step-set-default-org-at-login-screen
                step-unset-default-org
                step-logout
                step-verify-login-prompts-org))))


(defgroup user-tests

  (deftest "Admin creates a user"
    (user/create (uniqueify "autouser")   generic-user-details)
    
    (deftest "Admin creates a user with i18n characters"
      :data-driven true
      :blockers (open-bz-bugs "868906")
      
      (fn [username]
        (user/create (uniqueify username)   generic-user-details))
      [["صالح"] ["Гесер"] ["洪"]["標準語"]])

    (deftest "Admin creates a user with a default organization"
      :blockers (open-bz-bugs "852119")
      
      (with-unique [org-name "auto-org"
                    env-name "environment"
                    username "autouser"]
        (organization/create org-name {:initial-env-name env-name})
        (user/create username (merge generic-user-details {:default-org org-name, :default-env env-name}))))

    (deftest "Admin changes a user's password"
      :blockers (open-bz-bugs "720469")

      (with-unique [username "edituser"]
        (user/create username generic-user-details)
        (user/edit username {:new-password "changedpwd"})))


    (deftest "Admin deletes a user"
      (with-unique [username "deleteme"]
        (user/create username generic-user-details)
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
                                 (user/create username generic-user-details))))
    
    (deftest "Two users with username that differs only in case are allowed (like unix)"
      :blockers (open-bz-bugs "857876")
      :data-driven true
      (fn [orig-name modify-case-fn]
        (with-unique [name orig-name]
          (user/create name generic-user-details)
          (user/create (modify-case-fn name) generic-user-details)))

      [["usr"     capitalize]
       ["yourusr" capitalize]
       ["usr"     upper-case]
       ["MyUsr"   upper-case]
       ["YOURUsr" lower-case]])
    

    (deftest "User's minimum password length is enforced"
      (expecting-error (common/errtype :katello.notifications/password-too-short)
                       (user/create (uniqueify "insecure-user") {:password "abcd", :email "me@my.org"})))


    (deftest "Admin assigns a role to user"
      (with-unique [username "autouser"]
        (user/create username generic-user-details)
        (user/assign {:user username, :roles ["Administrator"]}))))

  default-org-tests)
