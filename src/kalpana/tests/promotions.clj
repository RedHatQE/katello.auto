(ns kalpana.tests.promotions
  (:require [kalpana.tasks :as tasks]
            [kalpana.rest :as rest]
            [kalpana.api-tasks :as api])
  (:import [org.testng.annotations Test BeforeClass])
  (:use [kalpana.conf :only [config]]
        [test-clj.testng :only [gen-class-testng data-driven]]
        [com.redhat.qe.verify :only [verify]]))

(def product-data-url "http://axiom.rdu.redhat.com/git/gitweb.cgi?p=kalpana;a=blob_plain;f=playpen/test-data/products.json;hb=HEAD")

(def product-name (atom nil))

(def provider-name (atom nil))

(defn ^{BeforeClass {:groups ["promotions"]}} setup [_]
  (reset! product-name (tasks/timestamp "MyProduct"))
  (reset! provider-name (tasks/timestamp "promotion-cp"))
  (api/create-provider @provider-name "test provider for promotions"
                       "http://blah.com" "Red Hat"
                       (@config :admin-user) (@config :admin-password)))

(defn create-product [name provider-name]
  (let [product (->> (rest/get product-data-url) :products first)
        updated-product (assoc product
                          :name name
                          :id name
                          :href (str "/products/" name))]
    (rest/post
     (str (@config :server-url) "/api/providers/"
          (api/get-id-by-name :provider provider-name) "/import_products/")
     (@config :admin-user) (@config :admin-password)
     {:products [updated-product]})))

(defn verify-promote-content [from-env to-env content]
  (for [product-name (content :products)]
    (create-product product-name @provider-name))
  (tasks/promote-content from-env content)
  (verify (tasks/content-in-environment? to-env content)))

(data-driven
 verify-promote-content
 {org.testng.annotations.Test
  {:groups ["promotions"] :description
   "Takes content and promotes it from one env to another.
              Verifies that it shows up in the new env."}}
 [["Locker" "Root" {:products (set (tasks/timestamp "MyProduct" 3))}]])

(gen-class-testng)




(comment "not needed - dates are not required for api"
         (def date-fmt (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss.SSSZZZZ"))

         (defn formatted-date
           ([d] (.format date-fmt d))
           ([] (formatted-date (java.util.Date.)))))
