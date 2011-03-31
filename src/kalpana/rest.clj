(ns kalpana.rest
  (:require [clj-http.client :as httpclient]
            [clojure.contrib.json :as json])
  (:refer-clojure :exclude (get)))

(defn get [url & [req]]
  (-> (httpclient/get url (merge req {:accept :json})) :body json/read-json))

(defn post [url user pw body & [req]]
  (httpclient/post url (merge req {:body (json/json-str body)
                                   :basic-auth [user pw]
                                   :accept :json
                                   :content-type :json}))) 
(defn delete [url user pw & [req]]
  (-> (httpclient/delete url (merge req {:basic-auth [user pw]
                                      :accept :json
                                         :content-type :json}))
      :body))
