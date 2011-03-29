(ns kalpana.rest
  (:require [clj-http.client :as httpclient]
            [clojure.contrib.json :as json]))

(defn get [url & [req]]
  (-> (httpclient/get url (merge req {:accept :json})) :body json/read-json))

(defn post [url user pw body & [req]]
  (httpclient/post url (merge req {:body (json/json-str body)
                                   :basic-auth [user pw]
                                   :accept :json
                                   :content-type :json}))) 
