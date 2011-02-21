(ns kalpana.tests.setup
  (:use [kalpana.conf :only [init config]]
        [com.redhat.qe.auto.selenium.selenium :only [connect browser]]
        [test-clj.testng :only [gen-class-testng]]
        [clojure.contrib.string :only [split]])
  (:require [kalpana.tasks :as tasks])
  (:import [org.testng.annotations BeforeSuite]))


(defn ^{BeforeSuite {:groups ["setup"]}}
  start_selenium [_]
  (init)
  (let [sel-addr (@config :selenium-address)
        [host port] (split #":" sel-addr)] 
    (connect host (Integer/parseInt port) "" (@config :server-url))
    (browser start)
    (browser open (@config :server-url))))

(defn ^{BeforeSuite {:groups ["setup"] :dependsOnMethods ["start_selenium"]}} [_]
  (tasks/login (@config :admin-user) (@config :admin-password)))

