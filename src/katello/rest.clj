(ns katello.rest
  (:require [clj-http.client :as httpclient]
            [clojure.data.json :as json])
  (:refer-clojure :exclude (get)))

(defn get
  "Gets the url, and decodes JSON in the response body, returning a
  clojure datastructure."
  [url & [req]]
  (-> (httpclient/get url (merge req {:accept :json})) :body json/read-json))

(defn post
  "Encodes datastructure in body to JSON, posts to url, using user and pw. "
  [url user pw body & [req]]
  (-> (httpclient/post url (merge req {:body (json/json-str body)
                                       :basic-auth [user pw]
                                       :accept :json
                                       :content-type :json}))
      :body json/read-json))

(defn delete [url user pw & [req]]
  (-> (httpclient/delete url (merge req {:basic-auth [user pw]
                                         :accept :json
                                         :content-type :json}))
      :body))
