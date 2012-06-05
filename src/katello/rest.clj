(ns katello.rest
  (:require [clj-http.client :as httpclient]
            [http.async.client :as http]
            [http.async.client.cert :as cert]
            [clojure.data.json :as json])
  (:refer-clojure :exclude (get)))

(def ^:dynamic *client* nil)

(defmacro with-client-cert
  "Execute body and makes any included rest calls with the given certificate"
  [keystore keystorepassword certificate certificatealias & body]
  `(binding [*client* (http/create-client :ssl-context (cert/ssl-context :keystore-file ~keystore :keystore-password ~keystorepassword :certificate-file ~certificate :certificate-alias ~certificatealias))]
     (do
       ~@body)))

(defmacro with-client-auth
  "Execute body and make any included rest calls with the given auth"
  [user password & body]
  `(binding [*client* (http/create-client :auth {:user ~user :password ~password :preemptive true})]
     (do
       ~@body)))

(defmacro with-no-auth
  "Execute body and make any included rest calls with no auth"
  [ & body ]
  `(binding [*client* (http/create-client)]
     (do
       ~@body)))

(defn- read-json-safe [s]
  (try (json/read-json s)
       (catch Exception e {:failed-json-object s :exception e})))

(defmacro wait-for-string-and-decode-json
  "Execute body, wait for string, then decode json"
  [ & body ]
  `(do
     (-> ~@body
         http/await
         http/string
         read-json-safe)))

(defn get-with-params
  [url params & [req]]
  (with-open [client *client*]
    (wait-for-string-and-decode-json (http/GET client url :headers (merge req {:Accept "application/json"}) :query params))))

(defn get
  "Gets the url, and decodes JSON in the response body, returning a
   clojure datastructure"
   [url & [req]]
   (with-open [client *client*] 
    (-> (http/GET client url :headers (merge req {:Accept "application/json"}))
        http/await
        http/string
        read-json-safe)))

(defn post
    "Do the actual post"
    [url body & [req]]
    (with-open [client *client*]
      (-> (http/POST client url
                   :body (json/json-str body)
                   :headers (merge req {:Accept "application/json"
                                        :Content-Type "application/json"}))
          http/await
          http/string
          read-json-safe)))

(defn post-multipart
  "Encodes datastructure in body to JSON, posts to url, using user and pw. 
      To send multipart, parts should be passed as a vector of maps.
      Every map needs a :type entry, which can be one of 
      (:string|:file|:bytearray)
      Then, rest of map needs:
        :string - :name, :value, :charset (optional)
        :file - :name, :file, :mime-type, :charset
        :bytearray - :name, :file-name, :data, :mime-type, :charset
      See http://neotyk.github.com/http.async.client/docs.html#sec-2-2-2-3"
  [url parts & [req]]
    (with-open [client *client*]
      (-> (http/POST client url :body parts :headers (merge req {:Accept "application/json"}))
          http/await
          http/string
          read-json-safe)))

(defn put 
  "Encodes datastructure in body to JSON, puts to url"
  [url body & [req]]
  (with-open [client *client*]
    (-> (http/PUT client url
            :headers (merge req {:Accept "application/json"
                                 :Content-Type "application/json"})
            :body (json/json-str body))
        http/await
        http/string
        read-json-safe)))

(defn delete 
  "DELETEs to url"
  [url & [req]]
  (with-open [client *client*]
    (-> (http/DELETE client url :headers (merge req {:Accept "application/json"
                                                   :Content-Type "application/json"}))
        http/await
        http/string
        read-json-safe)))

  
