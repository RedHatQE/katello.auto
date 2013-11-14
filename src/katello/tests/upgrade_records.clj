(ns katello.tests.upgrade-records
  (:refer-clojure :exclude [fn])
  (:require [katello :as kt]
            (katello [ui :as ui]
                     [users :as user]
                     [rest :as rest]
                     [roles :as role]
                     [environments :as env]
                     [conf :refer [*upgraded*]]
                     [blockers :refer [bz-bugs bz-bug auto-issue]])
            [test.assert :as assert]
            [slingshot.slingshot :refer :all] 
            [test.tree.script :refer [deftest defgroup]]
            [webdriver :as browser])
  (:import [com.thoughtworks.selenium SeleniumException]))

;; Tests
(defn- create-role* [f name]
  (-> {:name name} kt/newRole f ui/create))

(def create-role (partial create-role* identity))
(def global (kt/newOrganization {:name "Global Permissions"}))

(defn upgraded? []
  *upgraded*)

(defgroup upgrade-tests 
  ;(deftest "Verify Organization")

  ;(deftest "Verify Environment")

  ;(deftest "Verify Product")

  ;(deftest "Verify Repository")

  (deftest "Verify User"
    (let [org   (kt/newOrganization {:name "upgrade-user-org"})
          env   (kt/newEnvironment  {:name "upgrade-user-env" :org org})
          roles [(kt/newRole {:name "upgrade-user-role"})
                 (kt/newRole {:name "upgrade-user-role2"})]
          userc (kt/newUser {:name "upgrade-user" :password "abcd1234" 
                             :email "me@my.org"})
          useru (kt/newUser {:name "upgrade-user" :password "abcd1234" :email "me@my.org"
                             :default-org org :default-env env
                             :roles roles})
         userv #katello.User{:id nil, 
                             :name "upgrade-user", 
                             :email "me@my.org", 
                             :password nil, 
                             :password-confirm nil, 
                             :default-org #katello.Organization{:id nil, :name "upgrade-user-org", :label nil, :description nil, :initial-env nil}, 
                             :default-env #katello.Environment{:id nil, :name "upgrade-user-env", :label nil, :description nil, :org nil, :prior #katello.Environment{:id nil, :name "Library", :label nil, :description nil, :org nil, :prior nil, :next nil}, :next nil}, 
                             :roles ("upgrade-user-role" "upgrade-user-role2")} ]
      (do
        (when (not (upgraded?))
          (doall (map rest/ensure-doesnt-exist
                      (into [org env userc] roles)))
          (ui/create-all-recursive roles)
          (ui/create-recursive org)
          (ui/create-recursive env)
          (ui/create-recursive userc)
          (ui/update* userc useru))
        (assert/is (= userv (ui/read userc))))))

 (deftest "Verify Role"
      (let [user (kt/newUser {:name "upgrade-role-user" :password "abcd1234" :email "me@my.org"})
            rolec (kt/newRole {:name "upgrade-role"})
            roleu (kt/newRole {:name "upgrade-role"
                 :permissions [{:org global
                                :name "blah2"
                                :resource-type "Organizations"
                                :verbs ["Read Organization"]}]
                 :users [user]
                              })
           rolev #katello.Role{:id nil, 
                               :name "upgrade-role", 
                               :users (["Remove" "upgrade-role-user"]), 
                               :permissions (({:name "blah2", 
                                               :resource-type "Organizations", 
                                               :verbs ["Read Organization"], :on "+ All", 
                                               :org #katello.Organization{:id nil, :name "Global Permissions", :label nil, :description nil, :initial-env nil}}))} ]
   (when (not (upgraded?))
      (doall (map rest/ensure-doesnt-exist [rolec user]))
      (ui/create user)
      (ui/create rolec)
      (ui/update* rolec roleu)) 
      (assert/is (= rolev (ui/read rolec))))))
  

