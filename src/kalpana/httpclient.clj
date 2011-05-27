(ns kalpana.httpclient
  (:refer-clojure :exclude (get))
  (:require [clj-http.client :as baseclient]
            [clj-http.core :as core]
            [clojure.contrib.json :as json]
            [clojure.contrib.logging :as log]
            [clojure.contrib.pprint :as pprint]))


;; A slight change to the default clj-http client, where logging is done
;; of the request and response.  It's usually enough so that you can turn
;; off the verbose apache logging

(defn wrap-req-log [client]
  (fn [{:keys [content-type] :as req}]
    (log/info (format "Making HTTP request: \n%s"
                       (with-out-str
                         (pprint/pprint (if (= content-type "application/json")
                                          (update-in req [:body] json/read-json)
                                          req)))))
    (client req)))

(defn json)
(defn wrap-resp-log [client]
  (fn [req]
    (let [{:keys [status body] :as resp} (client req)      
          bstr (String. body)
          pretty-body (try (-> (json/read-json bstr) pprint/pprint with-out-str)
               (catch Exception e bstr))]
      (if (or (not (clojure.core/get req :throw-exceptions true))
              (baseclient/unexceptional-status? status))
        (log/info (format "Got HTTP status %d with response body: \n%s" status
                         pretty-body ))
        (log/error (format "Got HTTP error status %d with response body: \n%s" status pretty-body)))
      resp)))

(defn wrap-request
  "Returns a batteries-included HTTP request function coresponding to the given
   core client. See client/client."
  [request]
  (-> request
      wrap-resp-log
      baseclient/wrap-redirects
      baseclient/wrap-exceptions
      baseclient/wrap-decompression
      baseclient/wrap-input-coercion
      baseclient/wrap-output-coercion
      wrap-req-log 
      baseclient/wrap-query-params
      baseclient/wrap-basic-auth
      baseclient/wrap-user-info
      baseclient/wrap-accept
      baseclient/wrap-accept-encoding
      baseclient/wrap-content-type
      baseclient/wrap-method
      baseclient/wrap-url))

(def request
  (wrap-request #'core/request))

(defn get
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (request (merge req {:method :get :url url})))

(defn head
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (request (merge req {:method :head :url url})))

(defn post
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (request (merge req {:method :post :url url})))

(defn put
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (request (merge req {:method :put :url url})))

(defn delete
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (request (merge req {:method :delete :url url})))
