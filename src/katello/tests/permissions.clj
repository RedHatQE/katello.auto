(ns katello.tests.permissions
  (:refer-clojure :exclude [fn])
  (:require (katello [validation :as v]
                     [api-tasks :as api]
                     [conf :as conf]
                     [tasks :refer :all]
                     [providers :as providers]
                     [environments :as environment]
                     [roles :as role]
                     [users :as user]
                     [ui-tasks :refer :all]
                     [organizations :as organization])
        [test.tree.script :refer :all] 
        [serializable.fn :refer [fn]]
        [tools.verify :refer [verify-that]]
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
  (fn [] (navigate page))) 

(defn- navigate-all [& pages]
  (map navigate-fn pages))

(defn- access-org [org]
  (fn [] (navigate :named-organization-page {:org-name org})))

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
        pw "password"]
    (api/with-admin
      (api/create-user username {:password pw
                                 :email (str username "@my.org")})
      (when setup (setup)))
    
    (role/create rolename)
    (role/edit rolename {:add-permissions permissions
                         :users [username]})
    
    (try
      (let [with-perm-results (do (user/login username pw)
                                  (api/with-creds username pw
                                    (try-all allowed-actions)))
            no-perm-results (try-all disallowed-actions)]
        (verify-that (and (every? denied-access? (vals no-perm-results))
                          (every? has-access? (vals with-perm-results)))))
      (finally
       (user/login conf/*session-user* conf/*session-password*)))))

(def create-an-env
  (fn [] (environment/create (uniqueify "blah") {:org-name (@conf/config :admin-org)})))

(def create-an-ak
  (fn [] (create-activation-key {:name (uniqueify "blah")
                                      :environment (first conf/*environments*)})))

(def create-a-st
  (fn [] (create-template {:name (uniqueify "blah")})))

(def create-a-user
  (fn [] (user/create (uniqueify "blah") {:password "password" :email "me@me.com"})))

(def access-test-data
  [(fn [] [:permissions [{:org "Global Permissions"
                         :permissions [{:resource-type "Organizations"
                                        :verbs ["Read Organization"]
                                        :name "orgaccess"}]}]
          :allowed-actions [(access-org (@conf/config :admin-org))]
          :disallowed-actions (conj (navigate-all :administration-tab :systems-tab :sync-status-page
                                                  :custom-content-providers-tab :system-templates-page
                                                  :changesets-page )
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
            :disallowed-actions (conj (navigate-all :administration-tab :systems-tab :sync-status-page
                                                    :custom-content-providers-tab :system-templates-page
                                                    :changesets-page )
                                      (fn [] (providers/create {:name "myprov"}))
                                      (fn [] (api/create-provider "myprov")))]))
   
   
   (vary-meta
    (fn [] [:permissions [{:org "Global Permissions"
                          :permissions [{:resource-type "Organizations"
                                         :verbs ["Register Systems"]
                                         :name "systemreg"}]}]
           :allowed-actions [(fn [] (api/with-admin-org
                                     (api/with-env (first conf/*environments*)
                                       (api/create-system (uniqueify "system") {:facts (api/random-facts)}))))
                             (navigate-fn :systems-all-page)]
           :disallowed-actions (conj (navigate-all :providers-tab :manage-organizations-page)
                                     (fn [] (organization/create (uniqueify "cantdothis"))))])
    assoc :blockers (open-bz-bugs "757775"))
   
   (vary-meta
    (fn [] [:permissions [{:org "Global Permissions"
                          :permissions [{:resource-type "Activation Keys"
                                         :verbs ["Read Activation Keys"]
                                         :name "akaccess"}]}]
           :allowed-actions [(navigate-fn :activation-keys-page)]
           :disallowed-actions (conj (navigate-all :manage-organizations-page :administration-tab
                                                   :systems-all-page :systems-by-environment-page
                                                   :redhat-repositories-page)
                                     create-an-ak)])
    assoc :blockers (open-bz-bugs "757817"))
   
   (vary-meta
    (fn [] [:permissions [{:org "Global Permissions"
                          :permissions [{:resource-type "Activation Keys"
                                         :verbs ["Administer Activation Keys"]
                                         :name "akmang"}]}]
           :allowed-actions [create-an-ak]
           :disallowed-actions (conj (navigate-all :manage-organizations-page :administration-tab
                                                   :systems-all-page :systems-by-environment-page
                                                   :redhat-repositories-page)
                                     (fn [] (organization/create (uniqueify "cantdothis"))))])
    assoc :blockers (open-bz-bugs "757817"))

   (fn [] [:permissions [{:org "Global Permissions"
                         :permissions [{:resource-type "System Templates"
                                        :verbs ["Read System Templates"]
                                        :name "stread"}]}]
          :allowed-actions [(navigate-fn :system-templates-page)]
          :disallowed-actions (conj (navigate-all :systems-tab :manage-organizations-page :administration-tab
                                                  :custom-content-providers-tab :sync-status-page :changesets-page)
                                    create-a-st
                                    (fn [] (organization/create (uniqueify "cantdothis")))
                                    create-an-env)])

   (fn [] [:permissions [{:org "Global Permissions"
                         :permissions [{:resource-type "System Templates"
                                        :verbs ["Administer System Templates"]
                                        :name "stmang"}]}]
          :allowed-actions [create-a-st]
          :disallowed-actions (conj (navigate-all :systems-tab :manage-organizations-page :administration-tab
                                                  :custom-content-providers-tab :sync-status-page :changesets-page)
                                    (fn [] (organization/create (uniqueify "cantdothis")))
                                    create-an-env)])
   
   (fn [] [:permissions [{:org "Global Permissions"
                         :permissions [{:resource-type "Users"
                                        :verbs ["Read Users"]
                                        :name "userread"}]}]
          :allowed-actions [(navigate-fn :users-page)]
          :disallowed-actions (conj (navigate-all :systems-tab :manage-organizations-page :roles-page
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
            :disallowed-actions (conj (navigate-all :systems-tab :manage-organizations-page :roles-page
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
            :disallowed-actions (conj (navigate-all :systems-tab :manage-organizations-page :roles-page
                                                    :content-management-tab)
                                      create-a-user)]))

   (fn [] (let [org (uniqueify "org")]
           [:permissions [{:org (@conf/config :admin-org)
                            :permissions [{:resource-type "Organizations"
                                           :verbs ["Read Organization"]
                                           :name "orgaccess"}]}]
            :setup (fn [] (api/create-organization org))
            :allowed-actions [(access-org (@conf/config :admin-org))]
            :disallowed-actions (conj (navigate-all :administration-tab :systems-tab :sync-status-page
                                                    :custom-content-providers-tab :system-templates-page
                                                    :changesets-page )
                                      (fn [] (organization/switch org))
                                      (fn [] (navigate :named-organization-page {:org-name org})))]))
   
   ])

;; Tests

(defgroup permission-tests
  
  (deftest "Create a role"
    (role/create (uniqueify "testrole")))

 
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

      verify-access
      access-test-data) ))
