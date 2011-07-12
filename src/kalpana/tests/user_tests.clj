(ns katello.tests.user-tests
  (:use [test-clj.testng :only [gen-class-testng]]
        [katello.tests.setup :only [beforeclass-ensure-admin]])
  (:require [katello.tasks :as tasks])
  (:import [org.testng.annotations Test BeforeClass]))

(beforeclass-ensure-admin)

(defn ^{Test {:groups ["users"]
              :description "Create a user."}} simple_create [_]
  (tasks/create-user (tasks/timestamp "autouser") "password"))

(defn ^{Test {:groups ["users" "blockedByBug-720469"]
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
