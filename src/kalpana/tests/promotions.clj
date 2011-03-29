(ns kalpana.tests.promotions
  (:require [kalpana.tasks :as tasks]
            [kalpana.rest :as rest])
  (:import [org.testng.annotations Test BeforeClass])
  (:use [kalpana.conf :only [config]]
        [test-clj.testng :only [gen-class-testng]]
        ;;[error.handler :only [with-handlers handle ignore]]
        [com.redhat.qe.verify :only [verify]]
        [inflections :only [pluralize]]))

(def date-fmt (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss.SSSZZZZ"))

(defn formatted-date
  ([d] (.format date-fmt d))
  ([] (formatted-date (java.util.Date.))))

(def product-data-url "http://axiom.rdu.redhat.com/git/gitweb.cgi?p=kalpana;a=blob_plain;f=playpen/test-data/products.json;hb=HEAD")

(defn get-id-by-name [entity-type entity-name]
  (let [plural-type (-> entity-type name pluralize)
        all-entities (rest/get (str (@config :server-url) "/api/" plural-type))]
    (some (fn [ent] (if (= (-> ent entity-type :name) entity-name)
                     (-> ent entity-type :id)
                     false))
          all-entities)))

(defn create-provider [name description repo-url type username password]
  (rest/post (str (@config :server-url) "/api/organizations/" (@config :admin-user) "/providers")
             (@config :admin-user) (@config :admin-password)
             {:provider {:name name
              :description description
              :repository_url repo-url
              :provider_type type
              :login_credential {:username username
                                 :password password}}}))

(def product-name (atom nil))
(def provider-name (atom nil))

(defn ^{BeforeClass {:groups ["promotions"]}} setup [_]
  (reset! product-name (tasks/timestamp "MyProduct"))
  (reset! provider-name (tasks/timestamp "promotion-cp"))
  (create-provider @provider-name "test provider for promotions"
                   "http://blah.com" "Red Hat"
                   (@config :admin-user) (@config :admin-password))
  (let [product (->> (rest/get product-data-url) :products first)
        updated-product (assoc product
                          :name @product-name
                          :id @product-name
                          :href (str "/products/" @product-name))]
    (rest/post (str (@config :server-url)
                    "/api/providers/" (get-id-by-name :provider @provider-name) "/import_products/")
                     (@config :admin-user) (@config :admin-password)
                     {:products [updated-product]})))

(defn ^{Test {:groups ["promotions"]}} promote_content [_]
  (let [content {:products #{@product-name}}]
    (tasks/promote-content "Locker" content)))

(gen-class-testng)
