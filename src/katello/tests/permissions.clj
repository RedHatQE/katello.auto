(ns katello.tests.permissions
  (:refer-clojure :exclude [fn])
  (:use [test.tree.builder :only [fn]]
        [com.redhat.qe.verify :only [verify-that]])
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
  (doall (for [f fs]
           (try (f)
                (catch Exception e e)))))

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
                                  (try-all allowed-actions))
            no-perm-results (try-all disallowed-actions)]
        (verify-that (and (every? denied-access? no-perm-results)
                          (every? has-access? with-perm-results))))
      (finally
       (tasks/login conf/*session-user* conf/*session-password*)))))



(def access-test-data
  [[(let [org-name (tasks/uniqueify "org-create-perm")]
      {:permissions [{:org "Global Permissions"
                      :permissions [{:resource-type "Organizations"
                                     :verbs ["Create Organization"]
                                     :name "orgcreate"}]}]
       :allowed-actions [(fn [] (tasks/create-organization org-name "mydescription"))]
       :disallowed-actions [(fn [] (tasks/delete-organization org-name))
                            (fn [] (tasks/create-provider {:name "myprov"}))]})]])
