(ns katello.tests.environments
  (:refer-clojure :exclude [fn])
  (:require [katello.api-tasks :as api])
  (:use katello.tasks
        katello.validation
        [katello.conf :only [config]]
        [katello.tests.providers :only [with-two-orgs]]
        [com.redhat.qe.verify :only [verify-that]]
        [serializable.fn :only [fn]]))

(def test-org-name (atom nil))
(def first-env "dev")

(def create-test-org 
  (fn []
    (api/with-admin-creds
      (api/create-organization
       (reset! test-org-name (uniqueify "env-test"))
       {:description "organization used to test environments."}))))

(def create
  (fn []
    (verify-success
     #(create-environment (uniqueify "simple-env")
                          {:org-name @test-org-name
                           :description "simple environment description"}))))

(def delete
  (fn []
    (let [env-name (uniqueify "delete-env")]
      (create-environment
       env-name
       {:org-name @test-org-name
        :description "simple environment description"})
      (verify-success
       #(delete-environment env-name {:org-name @test-org-name})))))

(def dupe-disallowed
  (fn [] (duplicate-disallowed create-environment
                              [(uniqueify "test-dup")
                               {:org-name @test-org-name
                                :description "dup env description"}]
                              (expect-error :name-must-be-unique-within-org))))

(def rename
  (fn []
    (let [env-name (uniqueify "rename")
          new-name (uniqueify "newname")]
      (create-environment env-name
                          {:org-name @test-org-name
                           :description "try to rename me!"})
      (edit-environment env-name {:org-name @test-org-name
                                  :new-name new-name})
      (navigate :named-environment-page
                {:org-name @test-org-name
                 :env-name new-name}))))

(def name-required
  (fn [] (name-field-required create-environment
                             [nil {:org-name @test-org-name
                                   :description "env description"}])))

(def create-same-name-diff-org
  (fn []
    (with-two-orgs (fn [env-name orgs]
                     (doseq [org orgs]                           
                       (switch-org org)
                       (create-environment env-name {:org-name org}))))))

(def delete-same-name-diff-org
  (fn []
    (with-two-orgs (fn [env-name orgs]
                     (doseq [org orgs]
                       (switch-org org)
                       (create-environment env-name {:org-name org}))
                     
                     (delete-environment env-name {:org-name (first orgs)})
                     (navigate :named-environment-page {:env-name env-name
                                                        :org-name (second orgs)})))))

(def delete-env-with-promoted-content
  (fn []
    (let [env-name (uniqueify "del-w-content")
          provider-name (uniqueify "prov")
          product-name (uniqueify "prod")
          repo-name (uniqueify "repo")]
      (api/with-admin
        (api/create-environment env-name {})
        (api/create-provider provider-name)
        (api/create-product product-name {:provider-name provider-name})
        (api/create-repo repo-name {:product-name product-name :url (@config :sync-repo)})
        (sync-repos [repo-name])
        (api/with-env env-name
          (api/promote {:products [{:product_id (api/get-id-by-name :product product-name)}]})))
      (delete-environment env-name {:org-name (@config :admin-org)}))))