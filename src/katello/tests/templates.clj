(ns katello.tests.templates
  (:refer-clojure :exclude [fn])
  (:require (katello [tasks :as tasks]
                     [validation :as v]
                     [api-tasks :as api])
            [clj-http.client :as http]
            [clojure.java.io :as io])
  (:use [test.tree.builder :only [fn data-driven]]
        [com.redhat.qe.verify :only [verify-that]]
        [com.redhat.qe.auto.bz :only [open-bz-bugs]]
        [katello.conf :only [config]]))

(def test-template-name (atom nil))
(def content (atom nil))
(def products (atom []))
(def create
  (fn []
    (tasks/create-template {:name (reset! test-template-name (tasks/uniqueify "template"))
                            :description "my test template"})))

(def setup-content
  (fn []
    (let [provider-name (tasks/uniqueify "template")
          cs-name (tasks/uniqueify "cs")]
      (api/with-admin
        (api/create-provider provider-name))
      (reset! products (tasks/uniqueify "templateProduct" 3 ))
        (doseq [product @products]
          (api/with-admin
            (api/create-product product {:provider-name provider-name
                                         :description "product to test templates"})))
        (api/with-admin
          (api/with-env "Development"
            (api/create-changeset cs-name)
            (api/add-to-changeset cs-name {:content {:products @products}})
            (api/promote-changeset cs-name) 180000 nil)))))

(def add-content
  (fn []
    (tasks/add-to-template @test-template-name {:products @products})))
