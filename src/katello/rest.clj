(ns katello.rest
  (:require [clj-http.client :as httpclient]
            [http.async.client :as http]
            [http.async.client.cert :as cert]
            [clojure.data.json :as json])
  (:use slingshot.slingshot)
  (:refer-clojure :exclude (get)))

(def ^:dynamic *client* nil)

(defmacro bind-client-with-open [client-val & body]
  `(binding [*client* ~client-val]
     (try ~@body
          (finally
           (.close *client*)))))

(defmacro with-client-cert
  "Execute body and makes any included rest calls with the given certificate"
  [keystore keystorepassword certificate certificatealias & body]
  `(bind-client-with-open (http/create-client :ssl-context (cert/ssl-context :keystore-file ~keystore :keystore-password ~keystorepassword :certificate-file ~certificate :certificate-alias ~certificatealias))
     ~@body))

(defmacro with-client-auth
  "Execute body and make any included rest calls with the given auth"
  [user password & body]
  `(bind-client-with-open (http/create-client :auth {:user ~user :password ~password :preemptive true})
     ~@body))

(defmacro with-no-auth
  "Execute body and make any included rest calls with no auth"
  [ & body ]
  `(bind-client-with-open (http/create-client)
     ~@body))

;;stolen from https://github.com/dakrone/clj-http/blob/master/src/clj_http/client.clj
(def exceptional-status?
  (complement #{200 201 202 203 204 205 206 207 300 301 302 303 307}))

(defn detect-exceptional-status [res]
  (if (-> res http/status :code exceptional-status?)
    (throw+ {:type :exceptional-http-status :response res})
    res))

(defn- read-json-safe [s]
  (try (json/read-json s)
       (catch Exception e {:failed-json-object s :exception e})))

(defn wait-for-string-and-decode-json
  "wait for response, then decode json"
  [response]
  (-> response
     http/await
     detect-exceptional-status
     http/string
     read-json-safe))

(defn get-with-params
  [url params & [req]]
  (wait-for-string-and-decode-json
   (http/GET *client* url :headers (merge req {:Accept "application/json"}) :query params)))

(defn get
  "Gets the url, and decodes JSON in the response body, returning a
   clojure datastructure"
   [url & [req]]
   (wait-for-string-and-decode-json
    (http/GET *client* url :headers (merge req {:Accept "application/json"}))))

(defn post
  "Do the actual post"
  [url body & [req]]
  (wait-for-string-and-decode-json
   (http/POST *client* url
              :body (json/json-str body)
              :headers (merge req {:Accept "application/json"
                                   :Content-Type "application/json"}))))

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
  (wait-for-string-and-decode-json
   (http/POST *client* url :body parts :headers (merge req {:Accept "application/json"}))))

(defn put 
  "Encodes datastructure in body to JSON, puts to url"
  [url body & [req]]
  (wait-for-string-and-decode-json
   (http/PUT *client* url
             :headers (merge req {:Accept "application/json"
                                  :Content-Type "application/json"})
             :body (json/json-str body))))

(defn delete 
  "DELETEs to url"
  [url & [req]]
  (wait-for-string-and-decode-json
   (http/DELETE *client* url :headers (merge req {:Accept "application/json"
                                                  :Content-Type "application/json"}))))

(defmethod print-method com.ning.http.client.AsyncHttpClient
  [o ^java.io.Writer w]
  (.write w (pr-str {:class (class o)
                     :config {:realm (-> o .getConfig .getRealm .toString)}})))
