(ns katello.tests.providers
  (:refer-clojure :exclude [fn])
  (:require [katello.tasks :as tasks]
            
            [katello.api-tasks :as api])
  (:use [test.tree :only [fn data-driven]]
        [com.redhat.qe.verify :only [verify-that]]
        [com.redhat.qe.auto.bz :only [blocked-by-bz-bugs]]
        [katello.validation :only [field-validation expect-error duplicate-disallowed variations]]))

(def test-provider-name (atom nil))
(def test-product-name (atom nil))

(def create-custom 
  (fn [] (tasks/create-provider (tasks/uniqueify "auto-cp")
                               "my description"
                               :custom)))

(def rename
  (fn [] (let [old-name (tasks/uniqueify "rename")
              new-name (tasks/uniqueify "newname")]
          (tasks/create-provider old-name "my description" :custom)
          (tasks/edit-provider old-name :new-name new-name)
          (let [current-providers (map :name (api/all-entities
                                              :provider
                                              "ACME_Corporation"))]
            (verify-that (and (some #{new-name} current-providers)
                              (not (some #{old-name} current-providers))))))))

(def delete
  (fn [] (let [cp-name (tasks/uniqueify "auto-cp-delete")]
          (tasks/create-provider cp-name
                                 "my description"
                                 :custom)
          (tasks/verify-success
           #(tasks/delete-provider cp-name)))))

(def setup-custom
  (fn [] (tasks/create-provider (reset! test-provider-name (tasks/uniqueify "cust"))
                               "my description" :custom)))

(def create-product
  (fn [] (tasks/add-product @test-provider-name
                           (reset! test-product-name (tasks/uniqueify "prod"))
                           "test product")))

(def create-repo
  (fn [] (tasks/add-repo @test-provider-name
                        @test-product-name
                        (tasks/uniqueify "repo")
                        "http://test.com/myurl")))
(def dupe-disallowed
  (fn []
    (duplicate-disallowed tasks/create-provider [(tasks/uniqueify "dupe") "mydescription" :custom])))

(def validation
  (fn  [pred & [name description repo-url type :as args]]
    (field-validation tasks/create-provider args pred)))

(defn validation-data []
  (concat
   [[(expect-error :name-cant-be-blank) nil "blah" :redhat "http://sdf.com"]
                                
    ^{:blockers (blocked-by-bz-bugs "703528")
      :description "Test that invalid URL is rejected."}
    [(expect-error :repository-url-invalid) (tasks/uniqueify "mytestcp") "blah" :redhat "@$#%$%&%*()[]{}"]
    ^{:blockers (blocked-by-bz-bugs "703528")
      :description "Test that invalid URL is rejected."}
    [(expect-error :repository-url-invalid) (tasks/uniqueify "mytestcp") "blah" :redhat "https://"]
    [(expect-error :repository-url-invalid) (tasks/uniqueify "mytestcp") "blah" :redhat "@$#%$%&%*("]

    [(expect-error :repository-url-invalid) (tasks/uniqueify "mytestcp2") "blah" :redhat nil]
    [(expect-error :only-one-redhat-provider-per-org) (tasks/uniqueify "mytestcp3") nil :redhat "http://sdf.com"]
    [tasks/success? (tasks/uniqueify "mytestcp4") nil :custom "http://sdf.com"]]
   (variations
    [tasks/success? (tasks/uniqueify "mytestcp5") :javascript :custom "http://sdf.com"])
   (variations                  
    [(expect-error :name-no-leading-trailing-whitespace) :trailing-whitespace nil  :custom "http://sdf.com"])
   (variations
    [(expect-error :name-must-not-contain-characters) :invalid-character nil :custom "http://sdf.com"])))

