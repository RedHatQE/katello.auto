(ns katello.tests.templates
  (:refer-clojure :exclude [fn])
  (:require (katello [validation :as v]
                     [api-tasks :as api])
            [clj-http.client :as http]
            [clojure.java.io :as io])
  (:use katello.tasks
        [test.tree.builder :only [data-driven]]
        [serializable.fn :only [fn]]
        [com.redhat.qe.verify :only [verify-that]]
        [bugzilla.checker :only [open-bz-bugs]]
        [katello.conf :only [*environments*]]))

(def test-template-name (atom nil))
(def content (atom nil))
(def products (atom []))
(def repos (atom []))

(def create
  (fn []
    (create-template {:name (reset! test-template-name (uniqueify "template"))
                            :description "my test template"})))

(def setup-content
  (fn []
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
            (api/promote content)))))))

(def add-content
  (fn []
    (add-to-template @test-template-name @repos)))
