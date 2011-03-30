(ns kalpana.api-tasks
  (:require [kalpana.rest :as rest])
  (:use [kalpana.conf :only [config]]
        [inflections :only [pluralize]]))

(defn uri-for-entity-type
  ([entity-type] (uri-for-entity-type entity-type nil))
  ([entity-type org-name]
     (str "/api/" (if (some #(= entity-type %) [:environment :provider])
                    (str "organizations/"
                         (or org-name
                             (throw (IllegalArgumentException.
                                     (str "Org name is required for this entity type: "
                                          entity-type))))
                         "/")
                    "")
          (-> entity-type name pluralize))))

(defn all-entities [entity-type & org-name]
  (rest/get
   (str (@config :server-url)
        (uri-for-entity-type entity-type org-name))))

(defn get-id-by-name [entity-type entity-name & org-name]
  (let [item->entity-fn (if (= entity-type :provider)
                          entity-type
                          identity) ]
    (some (fn [ent] (if (= (-> ent item->entity-fn :name)
                          entity-name)
                     (-> ent item->entity-fn :id)
                     false))
          (all-entities entity-type org-name))))

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

