(ns katello.tests.templates
  (:refer-clojure :exclude [fn])
  (:require (katello [tasks :as tasks]
                     [validation :as v]
                     [api-tasks :as api])
            [clj-http.client :as http]
            [clojure.java.io :as io])
  (:use [test.tree :only [fn data-driven]]
        [com.redhat.qe.verify :only [verify-that]]
        [com.redhat.qe.auto.bz :only [open-bz-bugs]]
        [katello.conf :only [config]]))

(def test-template-name (atom nil))
(def content (atom nil))

(def create
  (fn []
    (tasks/create-template {:name (reset! test-template-name (tasks/uniqueify "template"))
                            :description "my test template"})))

(def setup-content
  (fn []
    (api/create-provider )))

(def add-content
  (fn []
    (tasks/)))
