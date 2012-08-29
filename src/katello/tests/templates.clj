(ns katello.tests.templates
  (:refer-clojure :exclude [fn])
  (:require [clj-http.client :as http]
            [clojure.java.io :as io]
            [test.tree.script :refer :all] 
            [serializable.fn :refer [fn]]
            [bugzilla.checker :refer [open-bz-bugs]]
            (katello [api-tasks :as api] 
                     [tasks :refer :all] 
                     [ui-tasks :refer :all] 
                     [conf :refer [*environments*]])))

;; Variables

(def test-template-name (atom nil))
(def content (atom nil))
(def products (atom []))
(def repos (atom []))

;; Functions

(defn setup-custom-content []  
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
  :blockers (open-bz-bugs "765888")
  
  (deftest "Create a system template" 
    (create-template {:name (reset! test-template-name (uniqueify "template"))
                      :description "my test template"})

    (deftest "Add custom content to a system template"
      (setup-custom-content)
      (add-to-template @test-template-name @repos))))
