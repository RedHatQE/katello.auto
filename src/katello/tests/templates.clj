(ns katello.tests.templates
  (:refer-clojure :exclude [fn])
  (:require [katello.api-tasks :as api]
            [clj-http.client :as http]
            [clojure.java.io :as io])
  (:use katello.tasks
        test.tree.script
        [serializable.fn :only [fn]]
        [bugzilla.checker :only [open-bz-bugs]]
        [katello.conf :only [*environments*]]))

;; Variables

(def test-template-name (atom nil))
(def content (atom nil))
(def products (atom []))
(def repos (atom []))

;; Functions

(defn setup-content []  
  (let [provider-name (uniqueify "template")
        cs-name (uniqueify "cs")]
    (api/with-admin
      (api/create-provider provider-name))
    (reset! products (take 3 (unique-names "templateProduct")))
    (reset! repos [])
    (let [prods (doall
                 (for [product @products]
                   (api/with-admin
                     (let [created-prod (api/create-product
                                         product {:provider-name provider-name
                                                  :description "product to test templates"})
                           repo-name (uniqueify "templ")]
                       (api/create-repo repo-name {:product-name product :url "http://my.fakerepo.com/blah/blah"})
                       (swap! repos conj {:product product :repositories [repo-name]})
                       created-prod))))
          content {:products (for [prod prods] {:product_id (:id prod)})}]
      (api/with-admin
        (api/with-env (first *environments*)
          (api/promote content))))))

;; Tests

(defgroup template-tests
  :group-setup setup-content
  :blockers (open-bz-bugs "765888")
  
  (deftest "Create a system template" 
    (create-template {:name (reset! test-template-name (uniqueify "template"))
                      :description "my test template"})

    (deftest "Add content to a system template"
      (add-to-template @test-template-name @repos))))
