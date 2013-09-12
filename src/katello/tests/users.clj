(ns katello.tests.users
  (:require [katello :as kt]
            (katello [validation :refer :all] 
                     [organizations :as organization]
                     [notifications :as notification]
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
                     [conf :refer [config *session-org* *session-user* *environments*]] 
                     [navigation :as nav]
                     [blockers :refer [bz-bugs]]) 
            [katello.tests.useful :refer [create-all-recursive create-recursive new-manifest]]
            [slingshot.slingshot :refer [throw+ try+]]
            [test.tree.script :refer :all]
            [test.assert :as assert]
            [com.redhat.qe.auto.selenium.selenium :refer [browser]]
            [clojure.string :refer [capitalize upper-case lower-case]]))

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
    :uuid "b5acedf5-f7d5-bb34-10c3-2d91caa3f9c8"
    :tcms "201013"
    (-> (new-unique-user)
        create-org-and-user
        assign-admin
        set-default-org-at-login-screen
        logout-user
        verify-login-direct-to-default-org)
     

    (deftest "Unset default org for a user at login"
      :uuid "1701cd3b-d842-96f4-5ec3-bd105cf406bf"
      (-> (new-unique-user)
          create-org-and-user
          assign-admin
          set-default-org-at-login-screen
          (assoc :default-org :none)
          set-default-org-at-dashboard
          logout-user
          verify-login-prompts-org)))
  
  (deftest "Default Org - user can change default org (smoke test)"
    :uuid "705938b1-bd37-d114-10d3-22202195d710"
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
    :uuid "0a0da6f9-3a84-b4e4-27d3-119f7a43c141"
    :tcms "201585"
    (-> (new-unique-user)
        create-org-and-user 
        login-user)
    (assert/is (= (set (organization/switcher-available-orgs)) 
                  #{}))
    (login))

  (deftest "User's Favorite Organization"
    :uuid "1608619a-68b2-0b54-57db-aaf30b298c43"
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
    :uuid "19567fea-dc37-7974-9a6b-3e11e16fab47"
    :blockers (bz-bugs "915960")
    (-> (new-unique-user)
        create-user
        login-user
        (ui/update assoc :password "changedpwd"))))   

(defgroup user-tests
  
  (deftest "Admin creates a user"
    :uuid "e502a331-b905-7c94-a8c3-d4bca1094d20"
    (ui/create (uniqueify (assoc generic-user :name "user")))
    
    (deftest "Admin creates a user with i18n and html characters "
      :uuid "3d79f50b-f27b-4e44-fa4b-834568c214d7"
      :data-driven true
      :blockers (bz-bugs "868906")
      
      (fn [username]
        (ui/create (uniqueify (assoc generic-user :name username ))))
      [["صالح"] ["Гесер"] ["洪"]["標準語"] ["<a href='foo1'>Click Here></a>"]])

    (deftest "User validation"
      :uuid "99693586-885f-9124-6c9b-93490b1bb687"
      :data-driven true

      (fn [username expected-err]
        (expecting-error (common/errtype expected-err)
                         (ui/create (assoc generic-user :name username ))))
      [[(random-ascii-string 2) :katello.notifications/username-must-contain-3-char]
       [(random-ascii-string 129) :katello.notifications/name-128-char-limit]
       ["foo   " :katello.notifications/validation-error]
       ["   foo   " :katello.notifications/validation-error]
       ["" :katello.notifications/username-cant-be-blank]])

    (deftest "Admin creates a user with a default organization"
      :uuid "0ec513c6-d68e-fff4-3b6b-d7ee7a590308"      
      (with-unique [org (kt/newOrganization {:name "auto-org"})
                    env (kt/newEnvironment {:name "environment" :org org})
                    user (assoc generic-user
                           :name "autouser"
                           :default-org org
                           :default-env env)]
        (rest/create-all (list org env))
        (ui/create user)))
    
    (deftest "Check whether the users default-org & default-env gets updated"
      :uuid "52271ddb-7c65-0514-048b-cdaa334d4204"
      (with-unique [org (kt/newOrganization {:name "auto-org"})
                    env (kt/newEnvironment {:name "environment" :org org})
                    user (assoc generic-user
                           :name "autouser"
                           :default-org org
                           :default-env env)]
        (let [default-org-env (first *environments*)]
          (rest/create-all (list org env))
          (ui/create user)
          (create-recursive default-org-env)
          (ui/update user assoc :default-org *session-org* :default-env default-org-env)
          (assert/is (= (browser getText ::user/current-default-org) (:name *session-org*)))
          (rest/when-katello (assert/is (= (browser getText ::user/current-default-env) (:name default-org-env)))))))
    
    (deftest "Check whether the users email address gets updated"
      :uuid "23b69aad-209c-98d4-d993-c24c215a0e6a"
      :data-driven true
      (fn [input-loc new-email save?]
        (with-unique [org (kt/newOrganization {:name "auto-org"})
                      env (kt/newEnvironment {:name "environment" :org org})
                      user (assoc generic-user
                             :name "autouser"
                             :default-org org
                             :default-env env)]
        (let [expected-res #(-> % :type (= :success))]
          (rest/create-all (list org env))
          (ui/create user)
          (expecting-error expected-res
            (nav/go-to ::user/named-page user)              
            (common/save-cancel ::user/save-button ::user/cancel-button :users-update input-loc new-email save?)))))

      [[::user/email-text "abc@redhat.com" false]
       [::user/email-text "pnq@fedora.com" true]])
      

    (deftest "Admin changes a user's password"
      :uuid "f50a6ca1-8374-5f84-0e8b-92e886c4625c"
      :blockers (bz-bugs "720469")
      (with-unique [user (assoc generic-user :name "edituser")]
        (ui/create user)
        (ui/update user assoc :password "changedpwd")))

    (deftest "Admin deletes a user"
      :uuid "b0003693-bbee-00f4-8013-bd4197c99c0f"
      :blockers (bz-bugs "961122")
      (with-unique [user (assoc generic-user :name "deluser")]
        (ui/create user)
        (ui/delete user))

      (deftest "Admin who deletes the original admin account can still do admin things"
        :uuid "09ddafc9-a4cf-88f4-85f3-850d3ed3049c"
        :blockers (bz-bugs "868910")
        
        (let [admin @user/admin]
          (try
            (ui/delete admin)
            (with-unique [org (kt/newOrganization {:name "deleteme"})]
              (ui/create org)
              (ui/delete org))
            (finally (ui/create admin)
                     (assign-admin admin))))))

    (deftest "Two users with the same username is disallowed"
      :uuid "459f3f2d-af43-7364-161b-19593bd81005"
      :blockers (bz-bugs "738425")

      (with-unique [user (assoc generic-user :name "dupeuser")]
        (expecting-error-2nd-try (common/errtype :katello.notifications/name-taken-error)
                                 (ui/create user))))
    
    (deftest "Two users with username that differs only in case are allowed (like unix)"
      :uuid "09143923-ca60-0a04-8a23-ab562f72b67e"
      :blockers (bz-bugs "857876")
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
      :uuid "577e2d2d-1e6f-dc64-57eb-84d5b4358e4d"
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
      :uuid "bbb93943-2a82-ddd4-18c3-760192403e00"
      (expecting-error (common/errtype :katello.notifications/password-too-short)
                       (ui/create (uniqueify (assoc generic-user
                                               :name "insecure-user"
                                               :password "abcd")))))


    (deftest "Admin assigns a role to user"
      :uuid "2f2b1989-80b2-8ce4-b03b-b12fff438916"
      (-> (new-unique-user) create-user assign-admin))
  

    (deftest "Roles can be removed from user"
      :uuid "01f94473-dbe4-80b4-3e5b-8b89145a814c"
      (-> (new-unique-user), create-user, assign-admin, (ui/update assoc :roles #{}))))

  (deftest "Unassign admin rights to admin user and then login
               to find only dashboard menu"
    :uuid "07f27396-e2d8-39b4-47a3-9ffdb01d3a7e"
    :blockers (bz-bugs "916156")
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
  
  (deftest "ORG - USER Dependency Removal"
    :uuid "749d23d7-f806-48c1-8961-e1d0be60e49b"
    :blockers (bz-bugs "1001609")
    :data-driven true
    (fn [reorder]
      (let [user (-> (new-unique-user)
                      create-user
                      assign-admin)]
        (try+
         (login user {:org *session-org*})
         (let [manifest (new-manifest true)
               org (kt/org manifest)
               entities [org user]]
           (ui/create manifest)
           (login)
           (doseq [item (reorder entities)]
             (ui/delete item)))
         (finally (login)))))
    [[identity]
     [reverse]])
           
  (deftest "Assure left pane updates when users/roles are added/deleted"
    :uuid "05f27396-e1d8-33b4-44a3-9ffdb01d227e"
    (with-unique [user (assoc generic-user :name "user1")
                  role  (kt/newRole {:name "myrole"})]
      (ui/create-all (list user role))
      (nav/go-to ::user/page)
      (assert/is (some #(= (user :name) %) (common/extract-left-pane-list)))
      (ui/delete user)
      (assert/is (some #(not= (user :name) %) (common/extract-left-pane-list)))
      (nav/go-to ::role/page)
      (assert/is (some #(= (role :name) %) (common/extract-left-pane-list)))
      (ui/delete role)
      (assert/is (some #(not= (role :name) %) (common/extract-left-pane-list)))))

  user-settings default-org-tests)
