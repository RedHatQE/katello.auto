(ns kalpana.rest
  (:require [clj-http.client :as httpclient]
            [clojure.contrib.json :as json]
            [clojure.contrib.pprint :as pprint]
            [clojure.contrib.logging :as log])
  (:refer-clojure :exclude (get)))

(defmacro with-logs [ & body]
  `(let [r# (do ~@body)]
     (log/debug (str "Result of REST call: \n" (with-out-str (pprint/pprint r#))))
     r#))

(defn get
  "Gets the url, and decodes JSON in the response body, returning a
  clojure datastructure."
  [url & [req]]
  (with-logs
    (-> (httpclient/get url (merge req {:accept :json})) :body json/read-json)))

(defn post
  "Encodes datastructure in body to JSON, posts to url, using user and pw. "
  [url user pw body & [req]]
  (with-logs
    (-> (httpclient/post url (merge req {:body (json/json-str body)
                                      :basic-auth [user pw]
                                      :accept :json
                                         :content-type :json}))
        :body json/read-json)))

(defn delete [url user pw & [req]]
  (with-logs (-> (httpclient/delete url (merge req {:basic-auth [user pw]
                                                    :accept :json
                                                    :content-type :json}))
                 :body)))
