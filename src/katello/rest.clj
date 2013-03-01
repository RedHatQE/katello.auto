(ns katello.rest
  (:require [clj-http.client :as httpclient]
            [clojure.data.json :as json]
            [katello.conf :as conf]
            [slingshot.slingshot :refer [throw+]])
  (:refer-clojure :exclude (get)))

(defn- read-json-safe [s]
  (try (json/read-json s)
       (catch Exception e {:failed-json-object s :exception e})))

(defn wrap-session-auth
  "Add basic auth with the current session user."
  [method]
  (fn [url & [req]]
    (method url (assoc req
                  :basic-auth [conf/*session-user* conf/*session-password*]
                  :insecure?  true))))

(defn wrap-json-body
  "Convert the request :body to json string. also accept json response."
  [method]
  (fn [url & [req]]
    (method url
            (merge req
                   (if-let [body (:body req)]
                     {:body         (json/json-str body)
                      :content-type :json}
                     {})
                   {:accept :json}))))

(defn wrap-body-get
  "Return just the body of the response."
  [method]
  (fn [url & [req]]
    (:body (method url req))))

(defn wrap-json-decode
  "Decode the body of the response, assuming it's json, into a clojure datastructure."
  [method]
  (fn [url & [req]]
    (read-json-safe (method url req))))

(def get (-> httpclient/get
            wrap-session-auth
            wrap-json-body
            wrap-body-get
            wrap-json-decode))

(def post (-> httpclient/post
             wrap-session-auth
             wrap-json-body
             wrap-body-get
             wrap-json-decode))

(def put (-> httpclient/put
            wrap-session-auth
            wrap-json-body
            wrap-body-get
            wrap-json-decode))

(def delete (-> httpclient/delete
               wrap-session-auth
               wrap-json-body
               wrap-body-get))


(defprotocol CRUD
  "Create/read/update/delete operations on katello entities via the api"
  (create [x] "Create an entity in the api")
  (read [x] "Get details on an entity from the api")
  (update [x f & args] "Change an existing entity x via the api,
                        passing it thru f (with extra args), returns
                        true on success")
  (delete [x] "Delete an existing entity via the api")
  (id [e] "Returns the id of the given entity used for API calls")
  (query [e] "Searches the server by the entity's name, returning the full entity"))

(defn api-url [uri]
  (format "%s/%s" (@conf/config :server-url) uri ))

(defn url-maker [coll ent]
  "Creates a fn that when given an entity will call fs on the entity
   to get deps and look up the ids on those deps.  Then fill in the
   format with those ids, and make a url"
  (-> (for [[fmt fs] coll
            :let [ids (for [f fs] (-> ent f id))]
            :when (every? identity ids)]
           (apply format fmt ids))
      first
      (or (throw+ {:type ::incomplete-context
                   :msg (format "%s are required for entity %s"
                                (->> coll
                                     (map second)
                                     (interpose " or ")
                                     (apply str))
                      
                                ent)}))
      api-url))

(defn katello-id [f e]
  "Gets the id field (not literally :id, but whatever katello
   considers id - eg for orgs it's :label) from the entity, or if not
   present, queries the server first to get it."
  (or (f e)
      (-> e query f str)))

(def id-impl (partial katello-id :id))
(def label-impl (partial katello-id :label))

(defn query-by-name [url-fn e]
  (first (get (url-fn e)
              {:query-params {:name (:name e)}})))

(defn read-impl [read-url-fn ent]
  (merge ent (if-let [handle (id ent)]
               (get (read-url-fn ent))
               (query ent))))

(def default-id-impl {:id id-impl})
