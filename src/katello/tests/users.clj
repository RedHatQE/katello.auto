(ns katello.tests.users
  (:require [katello :as kt]
            (katello [validation :refer :all] 
                     [organizations :as organization]
                     [ui :as ui]
                     [navigation :as nav]
                     [rest :as rest]
                     [fake-content :as fake]
                     [login :refer [login logout logged-in? with-user-temporarily]]
                     [ui-common :as common]
                     [roles :as role]
                     [menu :as menu]
                     [users :as user]
                     [tasks :refer :all] 
                     [conf :refer [config *session-org* *session-user*]] 
                     [navigation :as nav]) 
            [katello.tests.useful :refer [create-all-recursive]]
            [test.tree.script :refer :all]
            [test.assert :as assert]
            [com.redhat.qe.auto.selenium.selenium :refer [browser]]
            [clojure.string :refer [capitalize upper-case lower-case]]
            [bugzilla.checker :refer [open-bz-bugs]]))

;;; Constants

(def generic-user (kt/newUser {:password "password", :email "blah@blah.com"}))

;;; Functions

;;; Each of these functions will operate on a User, perhaps modifying it, and returning it
;;; This is so that we can chain the functions together with -> 

(defn new-unique-user
  "Produce a new user data for use in later steps."
  []
  (with-unique [org (kt/newOrganization {:name "org"})
                user (assoc generic-user :name "user" :default-org org)]
    user))

(defn create-org-and-user
  "Creates user and an org in katello, but doesn't set the user's
  default org yet."
  [{:keys [default-org] :as user}]
  (rest/create-all (list user default-org))
  user)

(defn assign-admin [user]
  (ui/update user assoc :roles #{role/administrator}))

(defn set-default-org-at-login-screen
  [{:keys [default-org] :as user}]
  (login user  {:default-org default-org
                :org default-org})
  user) ;; return user for more steps

(defn logout-user [user]
  (logout)
  user)

(defn login-user [user]
  (login user)
  user)

(defn create-user [user]
  (rest/create user)
  user)

(defn verify-login-direct-to-default-org
  [{:keys [default-org] :as user}]
  (login user)
  (assert/is (= (nav/current-org) (:name default-org)))
  user)

(defn verify-login-prompts-org [user]
  (login user)
  (assert/is (= "Select an Organization" (browser getText ::ui/switcher)))
  user)

(defn verify-only-one-org [user]
  (assert/is (= (set (organization/switcher-available-orgs)) 
                (hash-set (nav/current-org))))
  user)
  
(defn set-default-org-at-dashboard [{:keys [default-org] :as user}]
  (organization/switch nil {:default-org default-org})
  user)

;;; Tests

(defgroup default-org-tests
  :test-teardown (fn [& _ ] (login))
  (deftest "Set default org for a user at login"
    :tcms "201013"
    (-> (new-unique-user)
        create-org-and-user
        assign-admin
        set-default-org-at-login-screen
        logout-user
        verify-login-direct-to-default-org)
     

    (deftest "Unset default org for a user at login"
      (-> (new-unique-user)
          create-org-and-user
          assign-admin
          set-default-org-at-login-screen
          (assoc :default-org :none)
          set-default-org-at-dashboard
          logout-user
          verify-login-prompts-org)))
  
  (deftest "Default Org - user can change default org (smoke test)"
    (-> (new-unique-user)
        create-org-and-user
        assign-admin
        set-default-org-at-login-screen
        logout-user
        verify-login-direct-to-default-org
        (assoc :default-org *session-org*)
        set-default-org-at-dashboard
        verify-login-direct-to-default-org))
    

  (deftest "Default Org - user w/o rights cannot change default org (smoke test)"
    :tcms "201585"
    (-> (new-unique-user)
        create-org-and-user 
        set-default-org-at-login-screen)
    (assert/is (= (set (organization/switcher-available-orgs)) 
                #{})))

  (deftest "User's Favorite Organization"
    :data-driven true
    (fn [saved-methods expected]
      (let [save-method->env (into (array-map)
                                   (for [save-method saved-methods]
                                     (vector save-method
                                             (kt/newEnvironment {:name "Dev",
                                                                 :org (-> {:name (name save-method)}
                                                                          kt/newOrganization
                                                                          uniqueify)}))))
            user (new-unique-user)
            ways-to-set-default {:login (fn [user {:keys [org]}]
                                          (login user {:org org :default-org org}))
                                 :settings (fn [user {:keys [org] :as env}]
                                             (ui/update user assoc
                                                        :default-org org,
                                                        :default-env env))
                                 :star (fn [_ {:keys [org]}]
                                         (organization/switch nil {:default-org org}))}]
        (create-all-recursive (conj (vals save-method->env) user))
        (ui/update user assoc :roles (list role/administrator))
        (with-user-temporarily user
          (doseq [[save-method env] save-method->env]
            ((ways-to-set-default save-method) user env))
          (verify-login-direct-to-default-org (assoc user :default-org (-> expected
                                                                           save-method->env
                                                                           kt/org))))))
    
    [[[:login :star :settings] :star]
     [[:login :settings] :login]
     [[:login :star] :star]
     [[:settings :star] :star]]))


(defgroup user-settings
  :test-teardown (fn [& _ ] (login))
 
  (deftest "User changes his password"
    :blockers (open-bz-bugs "915960")
    (-> (new-unique-user)
        create-user
        login-user
        (ui/update assoc :password "changedpwd"))))   

(defgroup user-tests
  
  (deftest "Admin creates a user"
    (ui/create (uniqueify (assoc generic-user :name "user")))
    
    (deftest "Admin creates a user with i18n characters"
      :data-driven true
      :blockers (open-bz-bugs "868906")
      
      (fn [username]
        (ui/create (uniqueify (assoc generic-user :name username ))))
      [["صالح"] ["Гесер"] ["洪"]["標準語"]])

    (deftest "User validation"
      :data-driven true

      (fn [username expected-err]
        (expecting-error (common/errtype expected-err)
                         (ui/create (assoc generic-user :name username ))))
      [[(random-string (int \a) (int \z) 2) :katello.notifications/username-must-contain-3-char]
       [(random-string (int \a) (int \z) 129) :katello.notifications/name-128-char-limit]
       ["foo   " :katello.notifications/validation-error]
       ["   foo   " :katello.notifications/validation-error]
       ["<a href='foo'>Click Here></a>" :katello.notifications/name-must-not-contain-html]
       ["" :katello.notifications/username-cant-be-blank]])

    (deftest "Admin creates a user with a default organization"
      :blockers (open-bz-bugs "852119")
      
      (with-unique [org (kt/newOrganization {:name "auto-org"})
                    env (kt/newEnvironment {:name "environment" :org org})
                    user (assoc generic-user
                           :name "autouser"
                           :default-org org
                           :default-env env)]
        (rest/create-all (list org env))
        (ui/create user)))

    (deftest "Admin changes a user's password"
      :blockers (open-bz-bugs "720469")
      (with-unique [user (assoc generic-user :name "edituser")]
        (ui/create user)
        (ui/update user assoc :password "changedpwd")))

    (deftest "Admin deletes a user"
      (with-unique [user (assoc generic-user :name "deluser")]
        (ui/create user)
        (ui/delete user))

      (deftest "Admin who deletes the original admin account can still do admin things"
        :blockers (open-bz-bugs "868910")
        
        (let [admin @user/admin]
          (try
            (ui/delete admin)
            (with-unique [org (kt/newOrganization {:name "deleteme"})]
              (ui/create org)
              (ui/delete org))
            (finally (ui/create admin)
                     (assign-admin admin))))))

    (deftest "Two users with the same username is disallowed"
      :blockers (open-bz-bugs "738425")

      (with-unique [user (assoc generic-user :name "dupeuser")]
        (expecting-error-2nd-try (common/errtype :katello.notifications/name-taken-error)
                                 (ui/create user))))
    
    (deftest "Two users with username that differs only in case are allowed (like unix)"
      :blockers (open-bz-bugs "857876")
      :data-driven true
      (fn [orig-name modify-case-fn]
        (with-unique [user (assoc generic-user :name orig-name)]
          (ui/create-all (list user (update-in user [:name] modify-case-fn)))))

      [["usr"     capitalize]
       ["yourusr" capitalize]
       ["usr"     upper-case]
       ["MyUsr"   upper-case]
       ["YOURUsr" lower-case]])
    
    (deftest "Delete user-notifications"
      :data-driven true
      
      (fn [delete-all?]
        (with-unique [user (assoc generic-user :name "autouser")]
          (rest/create user)
          (assign-admin user)
          (logout)
          (login user {:org *session-org*})
          (user/delete-notifications delete-all?)))
      
      [[true]
       [false]])
    
    
    (deftest "User's minimum password length is enforced"
      (expecting-error (common/errtype :katello.notifications/password-too-short)
                       (ui/create (uniqueify (assoc generic-user
                                               :name "insecure-user"
                                               :password "abcd")))))


    (deftest "Admin assigns a role to user"
      (-> (new-unique-user) create-user assign-admin))
  

    (deftest "Roles can be removed from user"
      (-> (new-unique-user), create-user, assign-admin, (ui/update assoc :roles #{}))))

  (deftest "Unassign admin rights to admin user and then login
               to find only dashboard menu"
    :blockers (open-bz-bugs "916156")
    (let [user (-> (new-unique-user)
                   rest/create
                   assign-admin)
          admin (user/admin)]
      (with-user-temporarily user
        (ui/update admin assoc :roles #{})
        (with-user-temporarily admin
          (let [not-showing? #(not (browser isElementPresent %))]
            (assert/is (every? not-showing? [::menu/systems-link ::menu/content-link ::menu/setup-link] ))))
        (assign-admin admin))))

  user-settings default-org-tests)



