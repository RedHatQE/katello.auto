(ns katello.tests.users
  (:refer-clojure :exclude [fn])
  (:use [test.tree :only [fn]])
  (:require [katello.tasks :as tasks]))

(def create
  (fn [] (tasks/create-user (tasks/uniqueify "autouser")
                           "password")))

(def edit
  (fn [] (let [username (tasks/uniqueify "autouser")]
          (tasks/create-user username {:password "password"})
          (tasks/edit-user username {:new-password "changedpw"}))))

(def delete
  (fn [] (tasks/delete-user (tasks/uniqueify "autouser")
                           "password")))

