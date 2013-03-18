(ns katello.tests.users
  (:require (katello [validation :refer :all] 
                     [organizations :as organization]
                     [ui :as ui]
                     [fake-content :as fake]
                     [login :refer [login logout logged-in?]]
                     [ui-common :as common]
                     [roles :as role]
                     [menu :as menu]
                     [users :as user]
                     [tasks :refer :all] 
                     [content-search :refer [list-available-orgs]]
                     [conf :refer [config]]
                     [api-tasks :as api]
                     [providers :as provider] 
                     [repositories :as repo]
                     [gpg-keys :as gpg-key]) 
            [test.tree.script :refer :all]
            [test.assert :as assert]
            [clojure.string :refer [capitalize upper-case lower-case]]
            [bugzilla.checker :refer [open-bz-bugs]]))

;;; Constants

(def generic-user-details {:password "password", :email "blah@blah.com"})

;;; Functions

(defn step-create-org-and-user [{:keys [username org roles default-org default-env]}]
  (api/create-user username generic-user-details)
  (api/create-organization org)
  (user/assign {:user username :roles (or roles ["Administrator"])})
  (when default-org 
    (user/assign-default-org-and-env username default-org default-env)))

(defn step-set-default-org-at-login-screen [{:keys [username org]}]
  (login username (:password generic-user-details) {:default-org org
                                                         :org org}))

(defn step-logout [_]
  (logout))

(defn step-verify-login-direct-to-default-org [{:keys [username org]}]
  (login username (:password generic-user-details))
  (assert/is (= (organization/current)
                org)))

(defn step-verify-login-direct-to-new-default-org [{:keys [username new-org]}]
  (login username (:password generic-user-details))
  (assert/is (= (organization/current)
                new-org)))

(defn step-verify-login-prompts-org [{:keys [username org]}]
  (expecting-error [:type :katello.login/login-org-required]
                   (login username (:password generic-user-details))))

(defn step-verify-only-one-org [_]
  (assert/is (= (list-available-orgs) 
                (organization/current))))

(defn step-verify-multiple-orgs [_]
  (assert/is (< 1 (count (list-available-orgs)))))
  
(defn step-set-default-org [{:keys [new-org]}]
  (organization/switch new-org {:default-org new-org}))

(defn step-unset-default-org [_]
  (organization/switch nil {:default-org :none}))

;;; Tests

(defgroup default-org-tests
  :test-teardown (fn [& _ ] (login))
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
                step-verify-login-prompts-org)))
  
    (deftest "Default Org - user can change default org (smoke test)"
      (do-steps(merge (uniqueify-vals 
                        {:username "deforg"
                         :org "usersorg"})
                        {:new-org "ACME_Corporation"})
                step-create-org-and-user
                step-set-default-org-at-login-screen
                step-logout
                step-verify-login-direct-to-default-org
                step-set-default-org
                step-verify-login-direct-to-new-default-org 
                ))
    
    (deftest "User's Favorite Organization"
      :data-driven true
      
      (fn [saved-org-with expected]
        (let [user (uniqueify "deforg")
              org {:login  (uniqueify "usersorg-login")
                    :star  (uniqueify "usersorg-star")
                    :settings  (uniqueify "usersorg-settings")}]
          (login)
          (api/create-user user generic-user-details)
          (user/assign {:user user :roles  ["Administrator"]})
          
          (api/create-organization (org :login))
          (if (saved-org-with :login)
              (step-set-default-org-at-login-screen {:username user :org (org :login)})
              (login user (:password generic-user-details) {:org (org :login)}))
          
          (when (saved-org-with :star)
            (api/create-organization (org :star))
            (step-set-default-org {:new-org (org :star)}))
          
          (when (saved-org-with :settings)
            (api/create-organization (org :settings)) 
            (try 
              (user/assign-default-org-and-env user (org :settings) nil)
              (catch Exception e)))
          
          (step-verify-login-direct-to-new-default-org {:username user :new-org (org expected)})))
      
      [[#{:login :star :settings} :star]
       [#{:login :settings} :login]
       [#{:login :star} :star]
       [#{:settings :star} :star]])
   
    (deftest "Default Org - user w/o rights cannot change default org (smoke test)"
      (let [user (uniqueify "deforg")
               org  (uniqueify "usersorg")]
           (do-steps {:username user
                      :org org
                      :roles []
                      :default-org "ACME_Corporation"
                     :default-env nil}
                   step-create-org-and-user
                   step-set-default-org-at-login-screen
                   step-verify-only-one-org
                   ))))

(defgroup user-settings
    :test-teardown (fn [& _ ] (login))
 
    (deftest "User changes his password"
      :blockers (open-bz-bugs "915960")
      (with-unique [username "edituser"]
        (user/create username generic-user-details)
        (login username (:password generic-user-details))
        (user/self-edit {:new-password "changedpwd"})
        (login username "changedpwd"))))   

(defgroup user-tests
  
  (deftest "Admin creates a user"
    (user/create (uniqueify "autouser")   generic-user-details)
    
    (deftest "Admin creates a user with i18n characters"
      :data-driven true
      :blockers (open-bz-bugs "868906")
      
      (fn [username]
        (user/create (uniqueify username)   generic-user-details))
      [["صالح"] ["Гесер"] ["洪"]["標準語"]])

    (deftest "User validation"
      :data-driven true

      (fn [username expected-err]
        (expecting-error (common/errtype expected-err)
                         (user/create username generic-user-details)))
      [[(random-string (int \a) (int \z) 2) :katello.notifications/username-must-contain-3-char]
       [(random-string (int \a) (int \z) 65) :katello.notifications/username-64-char-limit]
       ["foo   " :katello.notifications/validation-error]
       ["   foo   " :katello.notifications/validation-error]
       ["<a href='foo'>Click Here></a>" :katello.notifications/validation-error]
       ["#$%^" :katello.notifications/validation-error]
       ["" :katello.notifications/username-cant-be-blank]])

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
    
    (deftest "Delete user-notifications"
      :data-driven true
      
      (fn [delete-all?]
        (with-unique [username "autouser"]
          (let [password "abcd1234"]
            (user/create username {:password password :email "me@my.org"})
            (user/assign {:user username, :roles ["Administrator"]})
            (logout)
            (login username password {:org (@config :admin-org)})
            (user/delete-notifications delete-all?))))
      
      [[true]
       [false]])
    
    
    (deftest "User's minimum password length is enforced"
      (expecting-error (common/errtype :katello.notifications/password-too-short)
                       (user/create (uniqueify "insecure-user") {:password "abcd", :email "me@my.org"})))


    (deftest "Admin assigns a role to user"
      (with-unique [username "autouser"]
        (user/create username generic-user-details)
        (user/assign {:user username, :roles ["Administrator"]})))
  
     (deftest "Roles can be removed from user"
      (with-unique [username "autouser"]
        (user/create username generic-user-details)
        (user/assign {:user username, :roles ["Administrator" "Read Everything"]})
        (user/unassign {:user username, :roles ["Read Everything"]})))
     
     (deftest "Unassign admin rights to admin user and then login
               to find only dashboard menu"
       :blockers (open-bz-bugs "916156")
       
       (let [user (@config :admin-user)
             pass (@config :admin-password)
             new-user (uniqueify "autouser1")
             new-pass "admin123"
             menu-links [::menu/systems-link ::menu/content-link ::menu/setup-link]]
         (user/create new-user {:password new-pass :email "me@my.org"})
         (user/assign {:user new-user, :roles ["Administrator"]})
         (user/unassign {:user user, :roles ["Administrator"]})
         (try
           (login)
           (assert/is (menu/menu-does-not-exists? menu-links))
           (finally  
             (login new-user new-pass {:org (@config :admin-org)})
             (user/assign {:user user, :roles ["Administrator"]})
             (login)))))
     
     (deftest "Assure user w/o Manage Permissions cannot associate GPG keys"
       (let [user-name (@config :admin-user)
             new-user (uniqueify "gpgkeyuser1")
             new-pass "gpgkey123"
             gpg-key-name (uniqueify "test-key-text5")
             provider-name (uniqueify "provider")
             product-name  (uniqueify "product")
             repo-name (uniqueify "repo")
             role-name (uniqueify "role")
             organization (@config :admin-org)]
         (gpg-key/create gpg-key-name {:contents (slurp (@config :gpg-key))})
         (provider/create {:name provider-name})
         (provider/add-product {:provider-name provider-name
                                :name product-name})
         (repo/add-with-key {:provider-name provider-name
                             :product-name product-name
                             :name repo-name
                             :url (-> fake/custom-providers first :products first :repos second :url)
                             :gpgkey gpg-key-name})
         (user/create new-user {:password new-pass :email "me@my.org"})
         (role/create role-name)
         (role/edit role-name
                 {:add-permissions [{:org organization
                                     :permissions [{:name "blah1"
                                                    :resource-type "Organizations"
                                                    :verbs ["Administer GPG Keys"]}
                                                   {:name "blah2"
                                                    :resource-type "Providers"
                                                    :verbs ["Read Providers"]}]}]
                  :users [new-user]})
         (user/assign {:user new-user, :roles ["Read Everything"]})
         (login new-user new-pass {:org (@config :admin-org)})
         (assert/is (repo/check-for-newlink-and-addrepobutton? provider-name)))))      

user-settings
default-org-tests)