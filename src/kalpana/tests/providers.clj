(ns kalpana.tests.providers
  (:require [kalpana.tasks :as tasks]
            [kalpana.validation :as validate])
  (:import [org.testng.annotations Test])
  (:use [test-clj.testng :only [gen-class-testng data-driven]]
        [error.handler :only [with-handlers handle ignore]]
        [com.redhat.qe.verify :only [verify]]))

(defn ^{Test {:groups ["providers"]}} create_simple [_]
  (let [result-message (tasks/create-provider
                        (tasks/timestamp "auto-cp")
                        "my description"
                        :redhat
                        "http://myrepo.url.com/blah/")]
    (verify (string? result-message))))

(defn ^{Test {:groups ["providers"]}} delete_simple [_]
  (let [cp-name (tasks/timestamp "auto-cp-delete")]
    (tasks/create-provider
     cp-name
     "my description"
     :redhat
     "http://myrepo.url.com/blah/")
    (tasks/verify-success #(tasks/delete-provider cp-name))))

(defn validate [name description repo-url type username password expected-result]
  (validate/field-validation (fn []
                               (tasks/create-provider name description type repo-url)
                               :success) expected-result))

(data-driven validate {org.testng.annotations.Test {:groups ["providers"]}}
             [[nil "blah" "http://sdf.com" :redhat "admin" "admin" :name-cant-be-blank]
              [(tasks/timestamp "mytestcp") "blah" nil :redhat "admin" "admin" :repository-url-cant-be-blank]
             ;; [(tasks/timestamp "mytestcp") "blah" "http://sdf.com" :redhat nil "admin" :login-credential.username-cant-be-blank]
              ;;[(tasks/timestamp "mytestcp") "blah" "http://sdf.com" :redhat "admin" nil :login-credential.password-cant-be-blank]
              [(tasks/timestamp "mytestcp") nil "http://sdf.com" :redhat "admin" "admin" :success]
              [(tasks/timestamp "mytestcp") nil "http://sdf.com" :custom "admin" "admin" :success]])

(gen-class-testng)
