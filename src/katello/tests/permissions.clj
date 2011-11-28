(ns katello.tests.permissions
  (:refer-clojure :exclude [fn])
  (:use [test.tree.builder :only [fn]]
        [com.redhat.qe.verify :only [verify-that]]
        [com.redhat.qe.auto.bz :only [open-bz-bugs]])
  (:require (katello [tasks :as tasks]
                     [validation :as v]
                     [api-tasks :as api]
                     [conf :as conf]))
  (:import [com.thoughtworks.selenium SeleniumException]))

(def create-role
  (fn [] (tasks/create-role (tasks/uniqueify "testrole"))))

(def remove-role
  (fn [] (let [role-name (tasks/uniqueify "deleteme-role")]
          (tasks/create-role role-name)
          (tasks/remove-role role-name))))

(def edit-role
  (fn [] (let [user-name (tasks/uniqueify "role-user")
              role-name (tasks/uniqueify "edit-role")]
          (tasks/create-user user-name {:password "abcd1234" :email "me@my.org"})
          (tasks/create-role role-name)
          (tasks/edit-role role-name
                           {:add-permissions [{:org "Global Permissions"
                                               :permissions [{:name "blah2"
                                                              :resource-type "Organizations"
                                                              :verbs ["Access Organization"]}]}]
                            :users [user-name]}))))

(def missing-link?
  (fn [res]
    (-> res class (isa? SeleniumException))))

(def no-perm-user (atom nil))

(def setup-no-perm-user
  (fn [] (api/with-admin
     (apply
      api/create-user (reset! no-perm-user [(tasks/uniqueify "noperms")
                                            {:password "password"
                                             :email (str "noperm@my.org")}])))))

(def denied-access? (fn [r] (-> r class (isa? Throwable))))
(def has-access? (fn [r] (not (denied-access? r))))

(defn- try-all [fs]
  (zipmap fs (doall (for [f fs]
                      (try (f)
                           (catch Exception e e))))))

(defn- navigate [page]
  (with-meta (fn [] (tasks/navigate page))
                      {:type :test.tree.builder/serializable-fn
                       :test.tree.builder/source `(navigate ~page)})) 

(defn- navigate-all [& pages]
  (map navigate pages))



(defn verify-access "First tries all actions with a user with no permissions, to make sure they all fail.  Then gives a new user the permissions, and retries the actions to ensure they all succeed, finally tries out-of-bounds actions to make sure they still fail."
  [{:keys [permissions allowed-actions disallowed-actions]}]
  (let [rolename (tasks/uniqueify "role")
        username (tasks/uniqueify "user-perm")
        pw "password"]
    (api/with-admin (api/create-user username {:password pw
                                               :email (str username "@my.org")}))
    (tasks/create-role rolename)
    (tasks/edit-role rolename {:add-permissions permissions
                               :users [username]})
    (try
      (let [with-perm-results (do (tasks/login username pw)
                                  (api/with-creds username pw
                                    (try-all allowed-actions)))
            no-perm-results (try-all disallowed-actions)]
        (verify-that (and (every? denied-access? (vals no-perm-results))
                          (every? has-access? (vals with-perm-results)))))
      (finally
       (tasks/login conf/*session-user* conf/*session-password*)))))

(def create-env
  (fn [] (tasks/create-environment (tasks/uniqueify "blah") {:org-name (@conf/config :admin-org)})))

(def create-ak
  (fn [] (tasks/create-activation-key {:name (tasks/uniqueify "blah")
                                      :environment (@conf/config :first-env)})))

(def create-st
  (fn [] (tasks/create-template {:name (tasks/uniqueify "blah")})))

(def create-user
  (fn [] (tasks/create-user (tasks/uniqueify "blah") {:password "password" :email "me@me.com"})))


(def access-test-data
  [(fn [] [{:permissions [{:org "Global Permissions"
                          :permissions [{:resource-type "Organizations"
                                         :verbs ["Access Organization"]
                                         :name "orgaccess"}]}]
           :allowed-actions [(fn [] (tasks/navigate :named-organization-page {:org-name (@conf/config :admin-org)}))]
           :disallowed-actions (conj (navigate-all :administration-tab :systems-tab :sync-status-page
                                                   :custom-providers-tab :system-templates-page
                                                   :promotions-page )
                                     (fn [] (tasks/create-organization (tasks/uniqueify "cantdothis")))
                                     create-env)}])


   
   (fn [] [(let [org-name (tasks/uniqueify "org-create-perm")] ;;due to bz 756252 'create' means manage
            {:permissions [{:org "Global Permissions"
                            :permissions [{:resource-type "Organizations"
                                           :verbs ["Create Organization"]
                                           :name "orgcreate"}]}]
             :allowed-actions [(fn [] (tasks/create-organization org-name {:description "mydescription"}))
                               (fn [] (tasks/delete-organization org-name))
                               create-env]
             :disallowed-actions (conj (navigate-all :administration-tab :systems-tab :sync-status-page
                                                     :custom-providers-tab :system-templates-page
                                                     :promotions-page )
                                       (fn [] (tasks/create-provider {:name "myprov"}))
                                       (fn [] (api/create-provider "myprov")))})])
   
   
   (vary-meta
    (fn [] [{:permissions [{:org "Global Permissions"
                           :permissions [{:resource-type "Organizations"
                                          :verbs ["Register Systems"]
                                          :name "systemreg"}]}]
            :allowed-actions [(fn [] (api/with-admin-org
                                      (api/with-env (@conf/config :first-env)
                                        (api/create-system (tasks/uniqueify "system") (api/random-facts)))))
                              (navigate :systems-all-page)]
            :disallowed-actions (conj (navigate-all :providers-tab :organizations-tab)
                                      (fn [] (tasks/create-organization (tasks/uniqueify "cantdothis"))))}])
    assoc :blockers (open-bz-bugs "757775"))
   
   (vary-meta
    (fn [] [{:permissions [{:org "Global Permissions"
                           :permissions [{:resource-type "Organizations"
                                          :verbs ["Access all Activation Keys"]
                                          :name "akaccess"}]}]
            :allowed-actions [(navigate :activation-keys-page)]
            :disallowed-actions (conj (navigate-all :content-management-tab :organizations-tab :administration-tab
                                                    :systems-all-page :systems-by-environment-page)
                                      create-ak)}])
    assoc :blockers (open-bz-bugs "757817"))
   
   (vary-meta
    (fn [] [{:permissions [{:org "Global Permissions"
                           :permissions [{:resource-type "Organizations"
                                          :verbs ["Manage all Activation Keys"]
                                          :name "akmang"}]}]
            :allowed-actions [create-ak]
            :disallowed-actions (conj (navigate-all :content-management-tab :organizations-tab :administration-tab
                                                    :systems-all-page :systems-by-environment-page)
                                      (fn [] (tasks/create-organization (tasks/uniqueify "cantdothis"))))}])
    assoc :blockers (open-bz-bugs "757817"))

   (fn [] [{:permissions [{:org "Global Permissions"
                          :permissions [{:resource-type "System Templates"
                                         :verbs ["Read All System Templates"]
                                         :name "stread"}]}]
           :allowed-actions [(navigate :system-templates-page)]
           :disallowed-actions (conj (navigate-all :systems-tab :organizations-tab :administration-tab
                                                   :custom-providers-tab :sync-status-page :promotions-page)
                                     create-st
                                     (fn [] (tasks/create-organization (tasks/uniqueify "cantdothis")))
                                     create-env)}])

   (fn [] [{:permissions [{:org "Global Permissions"
                          :permissions [{:resource-type "System Templates"
                                         :verbs ["Manage All System Templates"]
                                         :name "stmang"}]}]
           :allowed-actions [create-st]
           :disallowed-actions (conj (navigate-all :systems-tab :organizations-tab :administration-tab
                                                   :custom-providers-tab :sync-status-page :promotions-page)
                                     (fn [] (tasks/create-organization (tasks/uniqueify "cantdothis")))
                                     create-env)}])
   
   (fn [] [{:permissions [{:org "Global Permissions"
                          :permissions [{:resource-type "Users"
                                         :verbs ["Access Users"]
                                         :name "userread"}]}]
           :allowed-actions [(navigate :users-tab)]
           :disallowed-actions (conj (navigate-all :systems-tab :organizations-tab :roles-tab
                                                   :content-management-tab)
                                     (fn [] (tasks/create-organization (tasks/uniqueify "cantdothis")))
                                     create-env
                                     create-user)}])

   (fn [] [{:permissions [{:org "Global Permissions"
                          :permissions [{:resource-type "Users"
                                         :verbs ["Create Users"]
                                         :name "userread"}]}]
           :allowed-actions [create-user
                             (fn [] (api/create-user (tasks/uniqueify "user") {:password "password"
                                                                              :email "blah@blah.com"}))]
           :disallowed-actions (conj (navigate-all :systems-tab :organizations-tab :roles-tab
                                                   :content-management-tab)
                                     (fn [] (let [username (tasks/uniqueify "deleteme")]
                                             (tasks/create-user username {:password "password" :email "mee@mee.com"})
                                             (tasks/delete-user username))))}])
   
   ])
