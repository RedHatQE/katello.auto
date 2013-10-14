(ns katello.rest
  (:require [katello :as kt]
            [clj-http.client :as httpclient]
            [clojure.data.json :as json]
            [katello.conf :as conf]
            [slingshot.slingshot :refer [try+ throw+]])
  (:refer-clojure :exclude (get read)))

(defn- read-json-safe [s]
  (try (json/read-json s)
       (catch Exception e {:failed-json-object s :exception e})))

(defn wrap-session-auth
  "Add basic auth with the current session user."
  [method]
  (fn [url & [req]]
    (method url (assoc req
                  :basic-auth [(:name conf/*session-user*) (:password conf/*session-user*)]
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

(def http-get (-> httpclient/get
                  wrap-session-auth
                  wrap-json-body
                  wrap-body-get
                  wrap-json-decode))

(def http-post (-> httpclient/post
             wrap-session-auth
             wrap-json-body
             wrap-body-get
             wrap-json-decode))

(def http-put (-> httpclient/put
            wrap-session-auth
            wrap-json-body
            wrap-body-get
            wrap-json-decode))

(def http-delete (-> httpclient/delete
               wrap-session-auth
               wrap-json-body
               wrap-body-get))


(defprotocol CRUD
  "Create/read/update/delete operations on katello entities via the api"
  (create [x] "Create an entity in the api")
  (read [x] "Get details on an entity from the api")
  (update* [x new-x] "Change an existing entity x via the api, to make
                      it match new-x, returns true on success")
  (delete [x] "Delete an existing entity via the api")
  (id [e] "Returns the id of the given entity used for API calls (does
           not query server if not present)")
  (query [e] "Searches the server by the entity's name, returning the full entity"))

;; Because protocols do not support varargs
(defn update [x f & args]
  (update* x (apply f x args)))

(defn exists? [ent]
  (try+
    (boolean (read ent))
    (catch [:type ::entity-not-found] _ false)))

(def not-exists? (complement exists?))

(defn create-all [ents]
  (doseq [ent ents]
    (create ent)))

(defn api-url [uri]
  (format "%s/%s" (@conf/config :server-url) uri ))

(defn get-id
  "Gets the id from the entity, querying the server first, if necessary."
  [ent]
  (or (id ent)
      (id (merge ent (query ent)))))

(def get-version-from-server
  (memoize
    (fn [url]
      (try
        (http-get url)
        (catch Exception e {:name "unknown"
                            :version "unknown"
                            :exception e})))))

(def get-version
  (fn [] (get-version-from-server (api-url "/api/version"))))

(defn is-headpin? []
  (-> (get-version) :name .toLowerCase (= "headpin")))

(def is-katello? (complement is-headpin?))

(defn url-maker [coll ent]
  "Creates a fn that when given an entity will call fs on the entity
   to get deps and look up the ids on those deps.  Then fill in the
   format with those ids, and make a url"
  (-> (for [[fmt fs] coll
            :let [ids (for [f fs] (some-> ent f get-id))]
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
      (some-> e query f str)))

(def id-field :id)
(def label-field :label)

(defn query-by [query-field-kw rec-field-kw url-fn e]
  (or (first (http-get (url-fn e)
                  {:query-params {query-field-kw (rec-field-kw e)}}))
      (throw+ {:type ::entity-not-found
               :entity e})))

(defn query-by-name [url-fn e]
  (query-by :name :name url-fn e))

(defn read-impl [read-url-fn ent]
  (merge ent (if (id ent)
               (http-get (read-url-fn ent))
               (query ent))))

(defmacro when-katello [& body]
  `(when (is-katello?) ~@body))

(defmacro when-headpin [& body]
  `(when (is-headpin?) ~@body))

(defn only-when-katello [f]
 (fn [& args] 
   (when (is-katello?)
     (apply f args))))

(defn katello-only
  "A function you can call from :blockers of any test so it will skip
   if run against a non-katello (eg SAM or headpin) deployment"
  [_]
  (if (->> (get-version) :name .toLowerCase (= "headpin"))
    ["This test is for Katello based deployments only and this is a headpin-based server."] []))

(defn ensure-exists [ent]
  {:pre [(satisfies? CRUD ent)]}
  (when-not (exists? ent)
    (create ent)))

(defn create-recursive
  "Recursively create in katello, all the entites that satisfy
   katello.rest/CRUD (innermost first).  Example, an env that contains
   a field for its parent org, the org would be created first, then
   the env." [ent & [{:keys [check-exist?] :or {check-exist? true}}]]
   (doseq [field (vals ent) :when (satisfies? CRUD field)]
     (create-recursive field))
   (if check-exist? 
     (ensure-exists ent)
     (create ent)))

(defn create-all-recursive [ents & [{:keys [check-exist?] :as m}]]
  (doseq [ent ents]
    (create-recursive ent m)))

(defn poll-task-untill-completed   [uuid pause repeats]
  (loop [finished false
         max-wait repeats]
    (Thread/sleep pause) 
    (if (or finished (= max-wait 0))
      false
      (recur        
        (= "finished" (:state (katello.rest/http-get  (katello.rest/api-url (format "/api/tasks/%s" uuid)))))
        (dec max-wait)))))
        
