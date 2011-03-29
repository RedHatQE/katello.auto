(ns kalpana.tests.promotions
  (:require [kalpana.tasks :as tasks]
            [kalpana.rest :as rest])
  (:import [org.testng.annotations Test BeforeClass])
  (:use [kalpana.conf :only [config]]
        [test-clj.testng :only [gen-class-testng]]
        ;;[error.handler :only [with-handlers handle ignore]]
        [com.redhat.qe.verify :only [verify]]
        [inflections :only [pluralize]]))

(def product-data-url "http://axiom.rdu.redhat.com/git/gitweb.cgi?p=kalpana;a=blob_plain;f=playpen/test-data/products.json;hb=HEAD")

(defn get-id-by-name [entity-type entity-name]
  (let [plural-type (-> entity-type name pluralize)
        all-entities (rest/get (str (@config :server-url) "/api/" plural-type))]
    ))
(def product-name (atom nil))

(defn ^{BeforeClass {:groups ["promotions"]}} setup [_]
  (reset! product-name (tasks/timestamp "MyProduct"))
  
  (let [product (->> (rest/get product-data-url) :products first)
        updated-product (assoc product
                          :name product-name
                          :id product-name
                          :href (str "/products/" product-name))]
    (rest/post (str (@config :server-url) "/api/providers/1/import_products/")
                     (@config :admin-user) (@config :admin-password)
                     {:products [updated-product]})))

(defn ^{Test {:groups ["promotions"]}} promote_predefined_content [_]
  (let [content {:errata #{"Erratum-3" "Erratum-8"}
                 :packages #{"Package-0"}
                 :kickstart-trees #{"Tree-0" "Tree-2"}}]
    (tasks/promote-content "Locker" content)))

(gen-class-testng)
