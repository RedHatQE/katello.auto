(ns katello.tests.providers
  (:refer-clojure :exclude [fn])
  (:require [katello.tasks :as tasks]
            [katello.validation :as validate]
            [katello.api-tasks :as api])
  (:use [test-clj.core :only [fn data-driven]]
        [com.redhat.qe.verify :only [verify-that]]
        [com.redhat.qe.auto.bz :only [blocked-by-bz-bugs]]))

(def test-provider-name (atom nil))
(def test-product-name (atom nil))

(defn test-provider [type]
  (let [result-message (tasks/create-provider
                        (tasks/uniqueify "auto-cp")
                        "my description"
                        type
                        (if (= type :redhat)
                          "http://myrepo.url.com/blah/" nil))]
    (verify-that (string? result-message))))

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
                           "test product"
                           "http://test.url"
                           true
                           true)))

(def validation
  (fn  [name description repo-url type  expected-result]
    (let [name (if (fn? name) (name) name)] ; uniqueifying at compile time defeats purpose of unique names
      (validate/field-validation       
       (fn []                           
         (tasks/create-provider name description type repo-url) 
         :success) expected-result))))

(def validation-data
  (concat
   [[nil "blah" "http://sdf.com" :redhat :name-cant-be-blank]
                                
    ^{:pre (blocked-by-bz-bugs "703528")
      :description "Test that invalid URL is rejected."}
    [#(tasks/uniqueify "mytestcp") "blah" "@$#%$%&%*()[]{}" :redhat :repository-url-invalid]
    ^{:pre (blocked-by-bz-bugs "703528")
      :description "Test that invalid URL is rejected."}
    [#(tasks/uniqueify "mytestcp") "blah" "https://" :redhat :repository-url-invalid]
    [#(tasks/uniqueify "mytestcp") "blah" "@$#%$%&%*(" :redhat :repository-url-invalid]

    [#(tasks/uniqueify "mytestcp2") "blah" nil :redhat :repository-url-cant-be-blank]
    [#(tasks/uniqueify "mytestcp3") nil "http://sdf.com" :redhat :only-one-redhat-provider-per-org]
    [#(tasks/uniqueify "mytestcp4") nil "http://sdf.com" :custom :success]]
   (validate/variations
    [#(tasks/uniqueify "mytestcp5") :javascript "http://sdf.com" :custom :success])
   (validate/variations                  
    [:trailing-whitespace nil  "http://sdf.com" :custom  :name-no-leading-trailing-whitespace])
   (validate/variations
    [:invalid-character nil "http://sdf.com" :custom :name-must-not-contain-characters])))

