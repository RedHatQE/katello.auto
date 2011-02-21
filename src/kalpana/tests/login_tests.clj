(ns kalpana.tests.login-tests
  (:use [kalpana.conf :only [init config]]
        [com.redhat.qe.auto.selenium.selenium :only [connect browser]]
        [test-clj.testng :only [gen-class-testng]]
        [clojure.contrib.string :only [split]])
  (:require [kalpana.tasks :as tasks])
  (:import [org.testng.annotations Test BeforeClass]))

(defn ^{BeforeClass {:groups ["setup"]}} logout [] )

(defn ^{Test {:groups ["login"]}}
  login_admin [_]
  (tasks/login (@config :admin-user) (@config :admin-password))
  (verify (= (success-message) "Login Successful")))

(defn ^{Test {:groups ["login"] :enabled false}}
  login_invalid [_]
  (tasks/login "invalid" "asdf1234"))

(gen-class-testng)
