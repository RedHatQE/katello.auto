(ns katello.tests.environments
  (:use [test-clj.testng :only [gen-class-testng]]
        [com.redhat.qe.verify :only [verify]]
        [katello.tests.setup :only [beforeclass-ensure-admin]])
  (:require [katello.tasks :as tasks]
            [katello.api-tasks :as api]
            [katello.validation :as validate])
  (:import [org.testng.annotations Test BeforeClass]))

(def test-org-name (atom nil))
(def locker "Locker")
(def root "dev")

(beforeclass-ensure-admin)

(defn ^{BeforeClass {:groups ["setup"]}}
  create_test_org [_]
  (api/create-organization (reset! test-org-name (tasks/timestamp "env-test")) "organization used to test environments."))

(defn ^{Test {:groups ["environments" "blockedByBug-693797" "blockedByBug-707274" ]}} create_simple [_]
  (tasks/verify-success
   #(tasks/create-environment @test-org-name
                              (tasks/timestamp "simple-env")
                              "simple environment description")))

(defn ^{Test {:groups ["environments" "blockedByBug-690937"] :dependsOnMethods ["create_simple"]}}
  delete_simple [_]
  (let [env-name (tasks/timestamp "delete-env")]
    (tasks/create-environment @test-org-name
                              env-name
                              "simple environment description")
    (tasks/verify-success #(tasks/delete-environment @test-org-name env-name))))

(defn ^{Test {:groups ["environments" "validation" "blockedByBug-693797" "blockedByBug-695706"]}}
  name_required [_]
  (validate/name-field-required #(tasks/create-environment @test-org-name nil "env description")))

(defn ^{Test {:groups ["environments" "validation" "blockedByBug-690907" "blockedByBug-704392" ]
              :dependsOnMethods ["create_simple"]}}
  duplicate_disallowed [_]
  (let [env-name (tasks/timestamp "test-dup")]
    (validate/duplicate_disallowed
     #(tasks/create-environment @test-org-name env-name "dup env description")
     :expected-error :name-must-be-unique-within-org)))

(defn ^{Test {:groups ["environments"]
              :description "Rename an environment"
              :dependsOnMethods ["create_simple"]}}
  rename_environment [_]
  (let [env-name (tasks/timestamp "rename")
        new-name (tasks/timestamp "newname")]
    (tasks/create-environment @test-org-name env-name "try to rename me!")
    (tasks/edit-environment @test-org-name env-name :new-name new-name)
    (tasks/navigate :named-environment-page {:org-name @test-org-name
                                             :env-name new-name})))

(defn ^{Test {:groups ["environments" "blockedByBug-705495" "blockedByBug-717287"]
              :description "Create two promotion paths.  Take the last
              env in the 2nd path (z), and move it to the end of the
              first path.  Verify that items in the path still
              disallow editing to set z as a prior."
              :dependsOnMethods ["create_simple"]}}
  swap_paths [_]
  (let [org-name (tasks/timestamp "env2")
        env-name "myenv"]
    (tasks/create-organization org-name "org to hold test envs")
    (tasks/create-environment org-name root "first env" locker)
    (tasks/create-environment org-name env-name "test env" locker)
    (tasks/edit-environment org-name env-name :prior root)
    (let [available-priors (tasks/environment-other-possible-priors org-name root)]
      (verify (= available-priors #{locker})))))

(gen-class-testng)
