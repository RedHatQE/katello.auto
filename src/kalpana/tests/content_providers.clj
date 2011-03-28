(ns kalpana.tests.content-providers
  (:require [kalpana.tasks :as tasks])
  (:import [org.testng.annotations Test])
  (:use [test-clj.testng :only [gen-class-testng data-driven]]
        [error.handler :only [with-handlers handle ignore]]
        [com.redhat.qe.verify :only [verify]]))

(defn ^{Test {:groups ["content-providers"]}} create_simple [_]
  (verify (string? (tasks/create-content-provider
                    (tasks/timestamp "auto-cp")
                    "my description"
                    "http://myrepo.url.com/blah/"
                    "Red Hat"
                    "myuser"
                    "mypass"))))

(defn ^{Test {:groups ["content-providers"]}} delete_simple [_]
  (let [cp-name (tasks/timestamp "auto-cp-delete")]
    (tasks/create-content-provider
     cp-name
     "my description"
     "http://myrepo.url.com/blah/"
     "Red Hat"
     "myuser"
     "mypass")
    (let [message-after-delete (tasks/delete-content-provider cp-name)]
      (verify (string? message-after-delete)))))

(defn validate [name description repo-url type username password expected-result]
  (let [message-after-create (with-handlers
                 [(handle (if (keyword? expected-result)
                            expected-result nil) [e] (:type e))]
                 (tasks/create-content-provider
                  name description repo-url type username password)
                 :success)]
    (verify (= message-after-create expected-result))))

(data-driven validate {org.testng.annotations.Test {:groups ["content-providers"]}}
             [[nil "blah" "http://sdf.com" "Red Hat" "admin" "admin" :name-cant-be-blank]
              [(tasks/timestamp "mytestcp") "blah" nil "Red Hat" "admin" "admin" :repository-url-cant-be-blank]
              [(tasks/timestamp "mytestcp") "blah" "http://sdf.com" "Red Hat" nil "admin" :login-credential.username-cant-be-blank]
              [(tasks/timestamp "mytestcp") "blah" "http://sdf.com" "Red Hat" "admin" nil :login-credential.password-cant-be-blank]
              [(tasks/timestamp "mytestcp") nil "http://sdf.com" "Red Hat" "admin" "admin" :success]])

(gen-class-testng)
