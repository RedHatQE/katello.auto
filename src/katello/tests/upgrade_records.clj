(ns katello.tests.upgrade-records
  (:refer-clojure :exclude [fn])
  (:require [katello :as kt]
            (katello [ui :as ui]
                     [users :as user]
                     [roles :as role]
                     [blockers :refer [bz-bugs bz-bug auto-issue]])
            [test.assert :as assert]
            [test.tree.script :refer [deftest defgroup]]
            [test.tree :refer [blockers]]
            [webdriver :as browser])
  (:import [com.thoughtworks.selenium SeleniumException]))

;; Tests
(defn- create-role* [f name]
  (-> {:name name} kt/newRole f ui/create))

(def create-role (partial create-role* identity))
(def global (kt/newOrganization {:name "Global Permissions"}))

(defn upgrade? []
  false)

(defgroup upgrade-tests 

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

      (ui/create user)
      (ui/create rolec)
      (ui/update* rolec roleu) 
      (assert/is (= rolev (ui/read rolec)))))
  

  
  )   


