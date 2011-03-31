(ns kalpana.tests.promotions
  (:require [kalpana.tasks :as tasks]
            [kalpana.rest :as rest]
            [kalpana.api-tasks :as api])
  (:import [org.testng.annotations Test BeforeClass])
  (:use [kalpana.conf :only [config]]
        [test-clj.testng :only [gen-class-testng data-driven]]
        [com.redhat.qe.verify :only [verify]]))

(def product-data-url "http://axiom.rdu.redhat.com/git/gitweb.cgi?p=kalpana;a=blob_plain;f=playpen/test-data/products.json;hb=HEAD")

(def provider-name (atom nil))
(def root-next-env (atom nil))

(defn get-root-next-env [org]
  (let [rootid (api/get-id-by-name :environment "root" org)
        matches (filter #(= (:prior %) rootid) (api/all-entities :environment org))]
    (if (second matches)
      (throw (IllegalStateException.
              "Multiple envs have Root as their prior! See bz 692592."))
      (if (first matches)
        (-> matches first :name)
        (let [new-env-name (tasks/timestamp "promote-test-env")]
          (api/create-environment org new-env-name "for testing content promotion" :prior-env "root")
          new-env-name)))))

(defn ^{BeforeClass {:groups ["promotions"]}} setup [_]

  (reset! provider-name (tasks/timestamp "promotion-cp"))
  (api/create-provider @provider-name "test provider for promotions"
                       "http://blah.com" "Red Hat"
                       (@config :admin-user) (@config :admin-password))
  (reset! root-next-env (get-root-next-env "admin")))

(defn create-product [org name provider-name]
  (let [product (->> (rest/get product-data-url) :products first)
        updated-product (assoc product
                          :name name
                          :id name
                          :href (str "/products/" name))]
    (rest/post
     (str (@config :server-url) "/api/providers/"
          (api/get-id-by-name :provider provider-name org) "/import_products/")
     (@config :admin-user) (@config :admin-password)
     {:products [updated-product]})))

(defn verify_promote_content [org from-env to-env content]
  (doseq [product-name (content :products)]
    (create-product product-name @provider-name))
  (tasks/promote-content from-env content)
  (verify (tasks/content-in-environment? to-env content)))

(data-driven
 verify_promote_content
 {org.testng.annotations.Test
  {:groups ["promotions"] :description
   "Takes content and promotes it from one env to another.
              Verifies that it shows up in the new env."}}
 [["admin" "Locker" "Root" {:products (set (tasks/timestamp "MyProduct" 3))}]])

(defn ^{Test {:groups "promotions" :description
              "Promotes content thru multiple environments."}}
  promote_multiple [_]
  (let [product (set (tasks/timestamp "ProductMulti" 3))]
    (doseq [promotion [["admin" "Locker" "Root" product]
                       ["admin" "Root" @root-next-env product]]]
      (apply verify_promote_content promotion))))

(gen-class-testng)




(comment "not needed - dates are not required for api"
         (def date-fmt (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss.SSSZZZZ"))

         (defn formatted-date
           ([d] (.format date-fmt d))
           ([] (formatted-date (java.util.Date.)))))
