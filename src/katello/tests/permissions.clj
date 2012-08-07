(ns katello.tests.permissions
  (:refer-clojure :exclude [fn])
  (:use katello.tasks
        katello.ui-tasks
        test.tree.script
        [serializable.fn :only [fn]]
        [tools.verify :only [verify-that]]
        [bugzilla.checker :only [open-bz-bugs]])
  (:require (katello [validation :as v]
                     [api-tasks :as api]
                     [conf :as conf]))
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
    
    (create-role rolename)
    (edit-role rolename {:add-permissions permissions
                         :users [username]})
    
    (try
      (let [with-perm-results (do (login username pw)
                                  (api/with-creds username pw
                                    (try-all allowed-actions)))
            no-perm-results (try-all disallowed-actions)]
        (verify-that (and (every? denied-access? (vals no-perm-results))
                          (every? has-access? (vals with-perm-results)))))
      (finally
       (login conf/*session-user* conf/*session-password*)))))

(def create-an-env
  (fn [] (create-environment (uniqueify "blah") {:org-name (@conf/config :admin-org)})))

(def create-an-ak
  (fn [] (create-activation-key {:name (uniqueify "blah")
                                      :environment (first conf/*environments*)})))

(def create-a-st
  (fn [] (create-template {:name (uniqueify "blah")})))

(def create-a-user
  (fn [] (create-user (uniqueify "blah") {:password "password" :email "me@me.com"})))

(def access-test-data
  [(fn [] [:permissions [{:org "Global Permissions"
                         :permissions [{:resource-type "Organizations"
                                        :verbs ["Read Organization"]
                                        :name "orgaccess"}]}]
          :allowed-actions [(access-org (@conf/config :admin-org))]
          :disallowed-actions (conj (navigate-all :administration-tab :systems-tab :sync-status-page
                                                  :custom-content-providers-tab :system-templates-page
                                                  :promotions-page )
                                    (fn [] (create-organization (uniqueify "cantdothis")))
                                    create-an-env)])


   
   (fn [] (let [org-name (uniqueify "org-create-perm")] ;;due to bz 756252 'create' means manage
           [:permissions [{:org "Global Permissions"
                           :permissions [{:resource-type "Organizations"
                                          :verbs ["Administer Organization"]
                                          :name "orgcreate"}]}]
            :allowed-actions [(fn [] (create-organization org-name {:description "mydescription"}))
                              (fn [] (delete-organization org-name))
                              create-an-env]
            :disallowed-actions (conj (navigate-all :administration-tab :systems-tab :sync-status-page
                                                    :custom-content-providers-tab :system-templates-page
                                                    :promotions-page )
                                      (fn [] (create-provider {:name "myprov"}))
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
                                     (fn [] (create-organization (uniqueify "cantdothis"))))])
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
                                     (fn [] (create-organization (uniqueify "cantdothis"))))])
    assoc :blockers (open-bz-bugs "757817"))

   (fn [] [:permissions [{:org "Global Permissions"
                         :permissions [{:resource-type "System Templates"
                                        :verbs ["Read System Templates"]
                                        :name "stread"}]}]
          :allowed-actions [(navigate-fn :system-templates-page)]
          :disallowed-actions (conj (navigate-all :systems-tab :manage-organizations-page :administration-tab
                                                  :custom-content-providers-tab :sync-status-page :promotions-page)
                                    create-a-st
                                    (fn [] (create-organization (uniqueify "cantdothis")))
                                    create-an-env)])

   (fn [] [:permissions [{:org "Global Permissions"
                         :permissions [{:resource-type "System Templates"
                                        :verbs ["Administer System Templates"]
                                        :name "stmang"}]}]
          :allowed-actions [create-a-st]
          :disallowed-actions (conj (navigate-all :systems-tab :manage-organizations-page :administration-tab
                                                  :custom-content-providers-tab :sync-status-page :promotions-page)
                                    (fn [] (create-organization (uniqueify "cantdothis")))
                                    create-an-env)])
   
   (fn [] [:permissions [{:org "Global Permissions"
                         :permissions [{:resource-type "Users"
                                        :verbs ["Read Users"]
                                        :name "userread"}]}]
          :allowed-actions [(navigate-fn :users-page)]
          :disallowed-actions (conj (navigate-all :systems-tab :manage-organizations-page :roles-page
                                                  :content-management-tab)
                                    (fn [] (create-organization (uniqueify "cantdothis")))
                                    create-an-env
                                    create-a-user)])

   (fn [] (let [user (uniqueify "user")]
           [:setup (fn [] (api/create-user user {:password "password" :email "me@me.com"}))
            :permissions [{:org "Global Permissions"
                           :permissions [{:resource-type "Users"
                                          :verbs ["Modify Users"]
                                          :name "usermod"}]}]
            :allowed-actions [(fn [] (edit-user user {:new-email "blah@me.com"}))]
            :disallowed-actions (conj (navigate-all :systems-tab :manage-organizations-page :roles-page
                                                    :content-management-tab)
                                      (fn [] (let [username (uniqueify "deleteme")]
                                              (create-user username {:password "password" :email "mee@mee.com"})
                                              (delete-user username))))]))

   (fn [] (let [user (uniqueify "user")]
           [:permissions [{:org "Global Permissions"
                           :permissions [{:resource-type "Users"
                                          :verbs ["Delete Users"]
                                          :name "userdel"}]}]
            :setup (fn [] (api/create-user user {:password "password" :email "me@me.com"}))
            :allowed-actions [(fn [] (delete-user user))]
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
                                                    :promotions-page )
                                      (fn [] (switch-org org))
                                      (fn [] (navigate :named-organization-page {:org-name org})))]))
   
   ])

;; Tests

(defgroup permission-tests
  
  (deftest "Create a role"
    (create-role (uniqueify "testrole")))

 
  (deftest "Remove a role"
    (let [role-name (uniqueify "deleteme-role")]
      (create-role role-name)
      (remove-role role-name)))

 
  (deftest "Add a permission and user to a role"
    (with-unique [user-name "role-user"
                  role-name "edit-role"]
      (create-user user-name {:password "abcd1234" :email "me@my.org"})
      (create-role role-name)
      (edit-role role-name
                 {:add-permissions [{:org "Global Permissions"
                                     :permissions [{:name "blah2"
                                                    :resource-type "Organizations"
                                                    :verbs ["Read Organization"]}]}]
                  :users [user-name]}))

    (deftest "Verify user with specific permission has access only to what permission allows"
      :data-driven true

      verify-access
      access-test-data) ))