(ns katello.rest
  (:require [clj-http.client :as httpclient]
            [clojure.data.json :as json]
            [katello.conf :as conf])
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
  "Convert the request :body to json string."
  [method]
  (fn [url & [req]]
    (method url (if-let [body (:body req)]
                  (assoc req
                    :body         (json/json-str body)
                    :content-type :json
                    :accept       :json)))))

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

