(ns katello.tests.permissions
  (:refer-clojure :exclude [fn])
  (:use [test.tree.builder :only [fn]])
  (:require [katello.tasks :as tasks]
            [katello.validation :as v]))

(def create-role
  (fn [] (tasks/create-role (tasks/uniqueify "testrole"))))

(def remove-role
  (fn [] (let [role-name (tasks/uniqueify "deleteme-role")]
          (tasks/create-role role-name)
          (tasks/remove-role role-name))))

(def edit-role
  (fn [] (let [user-name (tasks/uniqueify "role-user")
              role-name (tasks/uniqueify "edit-role")]
          (tasks/create-user user-name {:password "abcd1234"})
          (tasks/create-role role-name)
          (tasks/edit-role role-name
                           {:add-permissions [{:org "Global Permissions"
                                               :permissions [{:name "blah2"
                                                              :resource-type "Organizations"
                                                              :verbs ["Access Organization"]}]}]
                            :users [user-name]}))))
