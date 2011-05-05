(ns kalpana.tests.user-tests
  (:use [test-clj.testng :only [gen-class-testng]])
  (:require [kalpana.tasks :as tasks]
;;            [kalpana.api-tasks :as api]
;;            [kalpana.validation :as validate]
            )
  (:import [org.testng.annotations Test BeforeClass]))


(defn ^{Test {:groups ["users"]
              :description "Create a user."}} simple_create [_]
  (tasks/create-user (tasks/timestamp "autouser") "password"))

(defn ^{Test {:groups ["users"]
              :description "Edit a user."}}
  simple_edit [_]
  (let [username (tasks/timestamp "autouser")]
    (tasks/create-user username "password")
    (tasks/edit-user username :new-password "changedpw")))

(defn ^{Test {:enabled false
              :groups ["users"]
              :description "Delete a user."}} simple_delete [_]
              (tasks/delete-user (tasks/timestamp "autouser") "password"))

(gen-class-testng)
