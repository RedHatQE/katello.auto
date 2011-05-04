(ns kalpana.api-tasks
  (:require [kalpana.rest :as rest]
            [clojure.contrib.logging :as log])
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
  (log/info (str "Retrieving all " (-> entity-type name pluralize) "."))
  (rest/get
   (str (@config :server-url)
        (uri-for-entity-type entity-type org-name))
   {:basic-auth [(@config :admin-user) (@config :admin-password)]}))

(defn get-id-by-name [entity-type entity-name & [org-name]]
  (log/info (str "Getting id for " (name entity-type) " "
                 entity-name (if org-name (str " in org " org-name) "")))
  (or (some (fn [ent] (if (= (:name ent) entity-name)
                    (ent :id)
                    false))
            (all-entities entity-type org-name))
      (throw (RuntimeException. (str "No matches for " (name entity-type) " named " entity-name)))))

(defn create-content-provider [org-name api-user api-password
                               & {:keys [name description repo-url type]}]
  (log/info (str "Creating provider " name))
  (rest/post
   (str (@config :server-url) (uri-for-entity-type :provider org-name))
   api-user api-password
   {:provider {:name name
               :description description
               :repository_url repo-url
               :provider_type type}}))

(defn create-environment [name org-name api-user api-password & {:keys [description prior-env]}]
  (rest/post
   (str (@config :server-url) (uri-for-entity-type :environment org-name))
   (@config :admin-user) (@config :admin-password)
   {:environment {:name name
                  :description description
                  :organization_id (get-id-by-name :organization org-name)
                  :prior (get-id-by-name :environment prior-env org-name)}}))

(defn delete-environment [org name]
  (rest/delete
   (str (@config :server-url) (uri-for-entity-type :environment (@config :admin-org)) "/" name)
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
