(ns kalpana.tests.organizations
  (:use [error.handler :only [with-handlers handle ignore]]
        [com.redhat.qe.verify :only [verify]]
        [test-clj.testng :only [gen-class-testng]])
  (:require [kalpana.tasks :as tasks])
  (:import [org.testng.annotations Test BeforeClass]))

(defn ^{Test {:groups ["organizations"]}} create_simple [_]
  (tasks/create-organization (tasks/timestamp "auto-org") "org description"))

(defn ^{Test {:groups ["organizations"]}} duplicate_disallowed [_]
  (let [org-name (tasks/timestamp "auto-org-dup")
        create #(tasks/create-organization org-name "org-description")]
    (create)
    (let [actual-result (with-handlers [(handle :name-taken-error [e] (:type e))]
                          (create))]
      (verify (= actual-result :name-taken-error)))))

(defn ^{Test {:groups ["organizations"]}} delete_simple [_]
  (let [org-name (tasks/timestamp "auto-org-del")]
    (tasks/create-organization org-name "org to delete immediately")
    (tasks/delete-organization org-name)))

(gen-class-testng)
