(ns katello.tests.permissions
  (:refer-clojure :exclude [fn])
  (:use [katello.tasks :exclude [navigate create-role edit-role remove-role create-user]]
        [serializable.fn :only [fn]]
        [com.redhat.qe.verify :only [verify-that]]
        [com.redhat.qe.auto.bz :only [open-bz-bugs]])
  (:require (katello [validation :as v]
                     [api-tasks :as api]
                     [conf :as conf]
                     [tasks :as tasks]))
  (:import [com.thoughtworks.selenium SeleniumException]))

(def create-role
  (fn [] (tasks/create-role (uniqueify "testrole"))))

(def remove-role
  (fn [] (let [role-name (uniqueify "deleteme-role")]
          (tasks/create-role role-name)
          (tasks/remove-role role-name))))

(def edit-role
  (fn [] (let [user-name (uniqueify "role-user")
              role-name (uniqueify "edit-role")]
          (tasks/create-user user-name {:password "abcd1234" :email "me@my.org"})
          (tasks/create-role role-name)
          (tasks/edit-role role-name
                           {:add-permissions [{:org "Global Permissions"
                                               :permissions [{:name "blah2"
                                                              :resource-type "Organizations"
                                                              :verbs ["Read Organization"]}]}]
                            :users [user-name]}))))

(def no-perm-user (atom nil))

(def setup-no-perm-user
  (fn [] (api/with-admin
          (apply
           api/create-user (reset! no-perm-user [(uniqueify "noperms")
                                                 {:password "password"
                                                  :email (str "noperm@my.org")}])))))

(def denied-access? (fn [r] (-> r class (isa? Throwable))))
(def has-access? (fn [r] (not (denied-access? r))))

(defn- try-all [fs]
  (zipmap fs (doall (for [f fs]
                      (try (f)
                           (catch Exception e e))))))

(defn- navigate [page]
  (fn [] (tasks/navigate page))) 

(defn- navigate-all [& pages]
  (map navigate pages))

(defn- access-org [org]
  (fn [] (tasks/navigate :named-organization-page {:org-name org})))

(defn verify-access "First tries all actions with a user with no permissions, to make sure they all fail.  Then gives a new user the permissions, and retries the actions to ensure they all succeed, finally tries out-of-bounds actions to make sure they still fail."
  [{:keys [permissions allowed-actions disallowed-actions setup]}] {:pre [permissions]}
  (let [rolename (uniqueify "role")
        username (uniqueify "user-perm")
        pw "password"]
    (api/with-admin
      (api/create-user username {:password pw
                                 :email (str username "@my.org")})
      (when setup (setup)))
    
    (tasks/create-role rolename)
    (tasks/edit-role rolename {:add-permissions permissions
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

(def create-env
  (fn [] (tasks/create-environment (uniqueify "blah") {:org-name (@conf/config :admin-org)})))

(def create-ak
  (fn [] (tasks/create-activation-key {:name (uniqueify "blah")
                                      :environment (@conf/config :first-env)})))

(def create-st
  (fn [] (tasks/create-template {:name (uniqueify "blah")})))

(def create-user
  (fn [] (tasks/create-user (uniqueify "blah") {:password "password" :email "me@me.com"})))




(def access-test-data
  [(fn [] [{:permissions [{:org "Global Permissions"
                          :permissions [{:resource-type "Organizations"
                                         :verbs ["Read Organization"]
                                         :name "orgaccess"}]}]
           :allowed-actions [(access-org (@conf/config :admin-org))]
           :disallowed-actions (conj (navigate-all :administration-tab :systems-tab :sync-status-page
                                                   :custom-providers-tab :system-templates-page
                                                   :promotions-page )
                                     (fn [] (create-organization (uniqueify "cantdothis")))
                                     create-env)}])


   
   (fn [] [(let [org-name (uniqueify "org-create-perm")] ;;due to bz 756252 'create' means manage
            {:permissions [{:org "Global Permissions"
                            :permissions [{:resource-type "Organizations"
                                           :verbs ["Administer Organization"]
                                           :name "orgcreate"}]}]
             :allowed-actions [(fn [] (create-organization org-name {:description "mydescription"}))
                               (fn [] (delete-organization org-name))
                               create-env]
             :disallowed-actions (conj (navigate-all :administration-tab :systems-tab :sync-status-page
                                                     :custom-providers-tab :system-templates-page
                                                     :promotions-page )
                                       (fn [] (create-provider {:name "myprov"}))
                                       (fn [] (api/create-provider "myprov")))})])
   
   
   (vary-meta
    (fn [] [{:permissions [{:org "Global Permissions"
                           :permissions [{:resource-type "Organizations"
                                          :verbs ["Register Systems"]
                                          :name "systemreg"}]}]
            :allowed-actions [(fn [] (api/with-admin-org
                                      (api/with-env (@conf/config :first-env)
                                        (api/create-system (uniqueify "system") (api/random-facts)))))
                              (navigate :systems-all-page)]
            :disallowed-actions (conj (navigate-all :providers-tab :organizations-tab)
                                      (fn [] (create-organization (uniqueify "cantdothis"))))}])
    assoc :blockers (open-bz-bugs "757775"))
   
   (vary-meta
    (fn [] [{:permissions [{:org "Global Permissions"
                           :permissions [{:resource-type "Activation Keys"
                                          :verbs ["Read Activation Keys"]
                                          :name "akaccess"}]}]
            :allowed-actions [(navigate :activation-keys-page)]
            :disallowed-actions (conj (navigate-all :content-management-tab :organizations-tab :administration-tab
                                                    :systems-all-page :systems-by-environment-page)
                                      create-ak)}])
    assoc :blockers (open-bz-bugs "757817"))
   
   (vary-meta
    (fn [] [{:permissions [{:org "Global Permissions"
                           :permissions [{:resource-type "Activation Keys"
                                          :verbs ["Administer Activation Keys"]
                                          :name "akmang"}]}]
            :allowed-actions [create-ak]
            :disallowed-actions (conj (navigate-all :content-management-tab :organizations-tab :administration-tab
                                                    :systems-all-page :systems-by-environment-page)
                                      (fn [] (create-organization (uniqueify "cantdothis"))))}])
    assoc :blockers (open-bz-bugs "757817"))

   (fn [] [{:permissions [{:org "Global Permissions"
                          :permissions [{:resource-type "System Templates"
                                         :verbs ["Read System Templates"]
                                         :name "stread"}]}]
           :allowed-actions [(navigate :system-templates-page)]
           :disallowed-actions (conj (navigate-all :systems-tab :organizations-tab :administration-tab
                                                   :custom-providers-tab :sync-status-page :promotions-page)
                                     create-st
                                     (fn [] (create-organization (uniqueify "cantdothis")))
                                     create-env)}])

   (fn [] [{:permissions [{:org "Global Permissions"
                          :permissions [{:resource-type "System Templates"
                                         :verbs ["Administer System Templates"]
                                         :name "stmang"}]}]
           :allowed-actions [create-st]
           :disallowed-actions (conj (navigate-all :systems-tab :organizations-tab :administration-tab
                                                   :custom-providers-tab :sync-status-page :promotions-page)
                                     (fn [] (create-organization (uniqueify "cantdothis")))
                                     create-env)}])
   
   (fn [] [{:permissions [{:org "Global Permissions"
                          :permissions [{:resource-type "Users"
                                         :verbs ["Read Users"]
                                         :name "userread"}]}]
           :allowed-actions [(navigate :users-tab)]
           :disallowed-actions (conj (navigate-all :systems-tab :organizations-tab :roles-tab
                                                   :content-management-tab)
                                     (fn [] (create-organization (uniqueify "cantdothis")))
                                     create-env
                                     create-user)}])

   (fn [] [(let [user (uniqueify "user")]
            {:setup (fn [] (api/create-user user {:password "password" :email "me@me.com"}))
             :permissions [{:org "Global Permissions"
                            :permissions [{:resource-type "Users"
                                           :verbs ["Modify Users"]
                                           :name "usermod"}]}]
             :allowed-actions [(fn [] (edit-user user {:new-email "blah@me.com"}))]
             :disallowed-actions (conj (navigate-all :systems-tab :organizations-tab :roles-tab
                                                     :content-management-tab)
                                       (fn [] (let [username (uniqueify "deleteme")]
                                               (katello.tasks/create-user username {:password "password" :email "mee@mee.com"})
                                               (katello.tasks/delete-user username))))})])

   (fn []
     [(let [user (uniqueify "user")]
        {:permissions [{:org "Global Permissions"
                        :permissions [{:resource-type "Users"
                                       :verbs ["Delete Users"]
                                       :name "userdel"}]}]
         :setup (fn [] (api/create-user user {:password "password" :email "me@me.com"}))
         :allowed-actions [(fn [] (delete-user user))]
         :disallowed-actions (conj (navigate-all :systems-tab :organizations-tab :roles-tab
                                                 :content-management-tab)
                                   create-user)})])

   (fn []
     [(let [org (uniqueify "org")]
        {:permissions [{:org (@conf/config :admin-org)
                        :permissions [{:resource-type "Organizations"
                                       :verbs ["Read Organization"]
                                       :name "orgaccess"}]}]
         :setup (fn [] (api/create-organization org))
         :allowed-actions [(access-org (@conf/config :admin-org))]
         :disallowed-actions (conj (navigate-all :administration-tab :systems-tab :sync-status-page
                                                 :custom-providers-tab :system-templates-page
                                                 :promotions-page )
                                   (fn [] (switch-org org))
                                   (fn [] (tasks/navigate :named-organization-page {:org-name org})))})])
   
   ])
