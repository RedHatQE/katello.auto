(ns kalpana.tests.organizations
  (:use [error.handler :only [with-handlers handle ignore]]
        [com.redhat.qe.verify :only [verify]]
        [test-clj.testng :only [gen-class-testng]])
  (:require [kalpana.tasks :as tasks]
            [kalpana.validation :as validate])
  (:import [org.testng.annotations Test BeforeClass]))

(defn ^{Test {:groups ["organizations"]}} create_simple [_]
  (tasks/create-organization (tasks/timestamp "auto-org") "org description"))

(defn ^{Test {:groups ["organizations"]}} delete_simple [_]
  (let [org-name (tasks/timestamp "auto-org-del")]
    (tasks/create-organization org-name "org to delete immediately")
    (tasks/delete-organization org-name)))

(defn ^{Test {:groups ["organizations" "validation" "blockedByBug-690907" ]}} duplicate_disallowed [_]
  (let [org-name (tasks/timestamp "test-dup")]
    (validate/duplicate_disallowed
     #(tasks/create-organization org-name "org-description"))))

(defn ^{Test {:groups ["organizations" "validation"]}} name_required [_]
  (validate/name-field-required #(tasks/create-organization nil "org description")))

(gen-class-testng)
