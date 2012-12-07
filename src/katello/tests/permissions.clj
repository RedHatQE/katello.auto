(ns katello.tests.permissions
  (:refer-clojure :exclude [fn])
  (:require (katello [navigation :as nav]
                     [validation :as v]
                     [api-tasks :as api]
                     [conf :as conf]
                     [tasks :refer :all]
                     [providers :as providers]
                     [environments :as environment]
                     [system-templates :as template]
                     [roles :as role]
                     [users :as user]
                     [organizations :as organization])
        [test.tree.script :refer :all] 
        [serializable.fn :refer [fn]]
        [test.assert :as assert]
        [bugzilla.checker :refer [open-bz-bugs]])
  (:import [com.thoughtworks.selenium SeleniumException]))


;;Variables

(def no-perm-user (atom nil))

;; Functions

(def denied-access? (fn [r] (-> r class (isa? Throwable))))

(def has-access? (complement denied-access?))

(defn- try-all [fs]
  (zipmap fs (doall (for [f fs]
                      (try (f)
                           (catch Exception e e))))))

(defn- navigate-fn [page]
  (fn [] (nav/go-to page))) 

(defn- navigate-all [& pages]
  (map navigate-fn pages))

(defn- access-org [org]
  (fn [] (nav/go-to :katello.organizations/named-page {:org-name org})))

(defn verify-access
  "Assigns a new user to a new role with the given permissions. That
   user is logs in, and tries the allowed-actions to ensure they all
   succeed, finally tries disallowed-actions to make sure they all
   fail. If any setup needs to be done to set up an action, a no-arg
   function can be passed in as setup. (for instance, if you're
   testing a permission to modify users, you need a test user to
   attempt to modify)."
  [& {:keys [permissions allowed-actions disallowed-actions setup]}] {:pre [permissions]}
  (let [rolename (uniqueify "role")
        username (uniqueify "user-perm")
        pw "password"
        try-all-with-user (fn [actions]
                            (conf/with-creds username pw
                              (user/login)
                              (try-all actions)) )]
    (api/create-user username {:password pw
                               :email (str username "@my.org")})
    (when setup (setup))
    
    (role/create rolename)
    (role/edit rolename {:add-permissions permissions
                         :users [username]})
    
    (try
      (let [with-perm-results (try-all-with-user allowed-actions)
            no-perm-results (try-all-with-user disallowed-actions)]
        (assert/is (and (every? denied-access? (vals no-perm-results))
                          (every? has-access? (vals with-perm-results)))))
      (finally
        (user/login)))))

(def create-an-env
  (fn [] (environment/create (uniqueify "blah") {:org-name (@conf/config :admin-org)})))

(def create-an-ak
  (fn [] (create-activation-key {:name (uniqueify "blah")
                                      :environment (first conf/*environments*)})))

(def create-a-st
  (fn [] (template/create {:name (uniqueify "blah")})))

(def create-a-user
  (fn [] (user/create (uniqueify "blah") {:password "password" :email "me@me.com"})))

(def access-test-data
  [(fn [] [:permissions [{:org "Global Permissions"
                         :permissions [{:resource-type "Organizations"
                                        :verbs ["Read Organization"]
                                        :name "orgaccess"}]}]
          :allowed-actions [(access-org (@conf/config :admin-org))]
          :disallowed-actions (conj (navigate-all :katello.systems/tab :sync-status-page
                                                  :custom-content-providers-tab :system-templates-page
                                                  :katello.changesets/page )
                                    (fn [] (organization/create (uniqueify "cantdothis")))
                                    create-an-env)])


   
   (fn [] (let [org-name (uniqueify "org-create-perm")] ;;due to bz 756252 'create' means manage
           [:permissions [{:org "Global Permissions"
                           :permissions [{:resource-type "Organizations"
                                          :verbs ["Administer Organization"]
                                          :name "orgcreate"}]}]
            :allowed-actions [(fn [] (organization/create org-name {:description "mydescription"}))
                              (fn [] (organization/delete org-name))
                              create-an-env]
            :disallowed-actions (conj (navigate-all :katello.systems/tab :sync-status-page
                                                    :custom-content-providers-tab :system-templates-page
                                                    :katello.changesets/page )
                                      (fn [] (providers/create {:name "myprov"}))
                                      (fn [] (api/create-provider "myprov")))]))
   
   
   (vary-meta
    (fn [] [:permissions [{:org "Global Permissions"
                          :permissions [{:resource-type "Organizations"
                                         :verbs ["Register Systems"]
                                         :name "systemreg"}]}]
           :allowed-actions [(fn [] (api/with-env (first conf/*environments*)
                                     (api/create-system (uniqueify "system") {:facts (api/random-facts)})))
                             (navigate-fn :katello.systems/page)]
           :disallowed-actions (conj (navigate-all :providers-tab :katello.organizations/page)
                                     (fn [] (organization/create (uniqueify "cantdothis"))))])
    assoc :blockers (open-bz-bugs "757775"))
   
   (vary-meta
    (fn [] [:permissions [{:org "Global Permissions"
                          :permissions [{:resource-type "Activation Keys"
                                         :verbs ["Read Activation Keys"]
                                         :name "akaccess"}]}]
           :allowed-actions [(navigate-fn :katello.activation-keys/page)]
           :disallowed-actions (conj (navigate-all :katello.organizations/page
                                                   :katello.systems/page :katello.systems/by-environment-page
                                                   :redhat-repositories-page)
                                     create-an-ak)])
    assoc :blockers (open-bz-bugs "757817"))
   
   (vary-meta
    (fn [] [:permissions [{:org "Global Permissions"
                          :permissions [{:resource-type "Activation Keys"
                                         :verbs ["Administer Activation Keys"]
                                         :name "akmang"}]}]
           :allowed-actions [create-an-ak]
           :disallowed-actions (conj (navigate-all :katello.organizations/page
                                                   :katello.systems/page :katello.systems/by-environment-page
                                                   :redhat-repositories-page)
                                     (fn [] (organization/create (uniqueify "cantdothis"))))])
    assoc :blockers (open-bz-bugs "757817"))

   (fn [] [:permissions [{:org "Global Permissions"
                         :permissions [{:resource-type "System Templates"
                                        :verbs ["Read System Templates"]
                                        :name "stread"}]}]
          :allowed-actions [(navigate-fn :system-templates-page)]
          :disallowed-actions (conj (navigate-all :katello.systems/tab :katello.organizations/page
                                                  :custom-content-providers-tab :sync-status-page :katello.changesets/page)
                                    create-a-st
                                    (fn [] (organization/create (uniqueify "cantdothis")))
                                    create-an-env)])

   (fn [] [:permissions [{:org "Global Permissions"
                         :permissions [{:resource-type "System Templates"
                                        :verbs ["Administer System Templates"]
                                        :name "stmang"}]}]
          :allowed-actions [create-a-st]
          :disallowed-actions (conj (navigate-all :katello.systems/tab :katello.organizations/page
                                                  :custom-content-providers-tab :sync-status-page :katello.changesets/page)
                                    (fn [] (organization/create (uniqueify "cantdothis")))
                                    create-an-env)])
   
   (fn [] [:permissions [{:org "Global Permissions"
                         :permissions [{:resource-type "Users"
                                        :verbs ["Read Users"]
                                        :name "userread"}]}]
          :allowed-actions [(navigate-fn :users-page)]
          :disallowed-actions (conj (navigate-all :katello.systems/tab :katello.organizations/page :katello.roles/page
                                                  :content-management-tab)
                                    (fn [] (organization/create (uniqueify "cantdothis")))
                                    create-an-env
                                    create-a-user)])

   (fn [] (let [user (uniqueify "user")]
           [:setup (fn [] (api/create-user user {:password "password" :email "me@me.com"}))
            :permissions [{:org "Global Permissions"
                           :permissions [{:resource-type "Users"
                                          :verbs ["Modify Users"]
                                          :name "usermod"}]}]
            :allowed-actions [(fn [] (user/edit user {:new-email "blah@me.com"}))]
            :disallowed-actions (conj (navigate-all :katello.systems/tab :katello.organizations/page :katello.roles/page
                                                    :content-management-tab)
                                      (fn [] (let [username (uniqueify "deleteme")]
                                              (user/create username {:password "password" :email "mee@mee.com"})
                                              (user/delete username))))]))

   (fn [] (let [user (uniqueify "user")]
           [:permissions [{:org "Global Permissions"
                           :permissions [{:resource-type "Users"
                                          :verbs ["Delete Users"]
                                          :name "userdel"}]}]
            :setup (fn [] (api/create-user user {:password "password" :email "me@me.com"}))
            :allowed-actions [(fn [] (user/delete user))]
            :disallowed-actions (conj (navigate-all :katello.systems/tab :katello.organizations/page :katello.roles/page
                                                    :content-management-tab)
                                      create-a-user)]))

   (fn [] (let [org (uniqueify "org")]
           [:permissions [{:org (@conf/config :admin-org)
                            :permissions [{:resource-type "Organizations"
                                           :verbs ["Read Organization"]
                                           :name "orgaccess"}]}]
            :setup (fn [] (api/create-organization org))
            :allowed-actions [(access-org (@conf/config :admin-org))]
            :disallowed-actions (conj (navigate-all :katello.systems/tab :sync-status-page
                                                    :custom-content-providers-tab :system-templates-page
                                                    :katello.changesets/page )
                                      (fn [] (organization/switch org))
                                      (fn [] (nav/go-to :katello.organizations/named-page {:org-name org})))]))
   
   (fn [] (let [org (uniqueify "org")]
           [:permissions [{:org org
                           :permissions [{:resource-type :all 
                                          :name "orgadmin"}]}]
            :setup (fn [] (api/create-organization org))
            :allowed-actions (conj (navigate-all :katello.systems/tab :sync-status-page
                                                   :custom-content-repositories-page :system-templates-page
                                                   :katello.changesets/page )
                                   (access-org org)
                                   (fn [] (environment/create (uniqueify "blah") {:org-name org})))
            :disallowed-actions [(access-org (@conf/config :admin-org))
                                 (fn [] (organization/switch (@conf/config :admin-org)))]]))
   
   ])

;; Tests

(defgroup permission-tests
  
  (deftest "Create a role"
    (role/create (uniqueify "testrole")))

  (deftest "Create a role with i18n characters"
      :data-driven true
      
      (fn [username]
          (role/create   (uniqueify username)))
      [["صالح"] ["Гесер"] ["洪"]["標準語"]])
 
  (deftest "Remove a role"
    (let [role-name (uniqueify "deleteme-role")]
      (role/create role-name)
      (role/delete role-name)))

 
  (deftest "Add a permission and user to a role"
    (with-unique [user-name "role-user"
                  role-name "edit-role"]
      (user/create user-name {:password "abcd1234" :email "me@my.org"})
      (role/create role-name)
      (role/edit role-name
                 {:add-permissions [{:org "Global Permissions"
                                     :permissions [{:name "blah2"
                                                    :resource-type "Organizations"
                                                    :verbs ["Read Organization"]}]}]
                  :users [user-name]}))

    (deftest "Verify user with specific permission has access only to what permission allows"
      :data-driven true
      :blockers (fn [t] (if (api/is-headpin?)
                         ((open-bz-bugs "868179") t)
                         []))

      verify-access
      access-test-data) ))
