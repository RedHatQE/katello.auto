(ns kalpana.tests.environments
  (:use [test-clj.testng :only [gen-class-testng]])
  (:require [kalpana.tasks :as tasks]
            [kalpana.api-tasks :as api]
            [kalpana.validation :as validate])
  (:import [org.testng.annotations Test BeforeClass]))

(def test-org-name (atom nil))

(defn ^{BeforeClass {:groups ["setup"]}}
  create_test_org [_]
  (api/create-organization (reset! test-org-name (tasks/timestamp "env-test-org")) "organization used to test environments."))

(defn ^{Test {:groups ["environments" "blockedByBug-693797"]}} create_simple [_]
  (tasks/verify-success
   #(tasks/create-environment @test-org-name
                              (tasks/timestamp "simple-env")
                              "simple environment description")))

(defn ^{Test {:groups ["environments" "blockedByBug-690937"] :dependsOnMethods ["create_simple"]}} delete_simple [_]
  (let [env-name (tasks/timestamp "delete-env")]
    (tasks/create-environment @test-org-name
                              env-name
                              "simple environment description")
    (tasks/verify-success #(tasks/delete-environment @test-org-name env-name))))

(defn ^{Test {:groups ["environments" "validation" "blockedByBug-690937"]}} name_required [_]
  (validate/name-field-required #(tasks/create-environment @test-org-name nil "env description")))

(defn ^{Test {:groups ["environments" "validation" "blockedByBug-690907"] :dependsOnMethods ["create_simple"]}} duplicate_disallowed [_]
  (let [env-name (tasks/timestamp "test-dup")]
    (validate/duplicate_disallowed #(tasks/create-environment @test-org-name env-name "dup env description"))))

(gen-class-testng)
