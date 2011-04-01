(ns kalpana.api-tasks
  (:require [kalpana.rest :as rest])
  (:use [kalpana.conf :only [config]]
        [inflections :only [pluralize]]))


(def product-data-url "http://axiom.rdu.redhat.com/git/gitweb.cgi?p=kalpana;a=blob_plain;f=playpen/test-data/products.json;hb=HEAD")

(defn uri-for-entity-type  
  [entity-type & [org-name]]
  (str "/api/" (if (some #(= entity-type %) [:environment :provider :product])
                 (str "organizations/"
                      (or org-name
                          (throw (IllegalArgumentException.
                                  (str "Org name is required for this entity type: "
                                       entity-type))))
                      "/")
                 "")
       (-> entity-type name pluralize)))

(defn all-entities
  "Returns a list of all the entities of the given entity-type.  If
  that entity type is part of an organization, the name of the org
  must also be passed in."
  [entity-type & [org-name]]
  (let [item->entity-fn (if (= entity-type :provider)
                          entity-type
                          identity) ]
    (map item->entity-fn (rest/get
                          (str (@config :server-url)
                               (uri-for-entity-type entity-type org-name))))))

(defn get-id-by-name [entity-type entity-name & [org-name]]
  (some (fn [ent] (if (= (ent :name) entity-name)
                   (ent :id)
                   false))
        (all-entities entity-type org-name)))

(defn create-provider [name description repo-url type username password]
  (rest/post
   (str (@config :server-url) (uri-for-entity-type :provider (@config :admin-user)))
   (@config :admin-user) (@config :admin-password)
   {:provider {:name name
               :description description
               :repository_url repo-url
               :provider_type type
               :login_credential {:username username
                                  :password password}}}))

(defn create-environment [org name description & {:keys [prior-env] :or {prior-env nil}}]
  (rest/post
   (str (@config :server-url) (uri-for-entity-type :environment (@config :admin-user)))
   (@config :admin-user) (@config :admin-password)
   {:environment {:name name
                  :description description
                  :organization_id (get-id-by-name :organization org)
                  :prior (get-id-by-name :environment prior-env org)}}))

(defn delete-environment [org name]
  (rest/delete
   (str (@config :server-url) (uri-for-entity-type :environment (@config :admin-user)) "/" name)
   (@config :admin-user) (@config :admin-password)))

(defn create-product [org name provider-name]
  (let [product (->> (rest/get product-data-url) :products first)
        updated-product (assoc product
                          :name name
                          :id name
                          :href (str "/products/" name))]
    (rest/post
     (str (@config :server-url) "/api/providers/"
          (get-id-by-name :provider provider-name org) "/import_products/")
     (@config :admin-user) (@config :admin-password)
     {:products [updated-product]})))

(defn create-organization [name description]
  (rest/post
   (str (@config :server-url) (uri-for-entity-type :organization))
   (@config :admin-user) (@config :admin-password)
   {:name name
    :description description}))
