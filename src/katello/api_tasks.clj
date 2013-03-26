(ns katello.api-tasks
  (:refer-clojure :exclude [read])
  (:require [slingshot.slingshot :refer [throw+]] 
            [inflections.core :refer [pluralize singularize]]
            [com.redhat.qe.auto.selenium.selenium :refer [loop-with-timeout]]
            [clojure.set :refer [index]]
            katello
            (katello [rest :as rest] 
                     [conf :refer [config *session-user* *session-org* ]] 
                     [tasks :refer [uniqueify chain]])))

(def ^:dynamic *env-id* nil)
(def ^:dynamic *product-id* nil)
(def ^:dynamic *repo-id* nil)



(defn assoc-if-set
  "Adds to map m just the entries from newmap where the value is not nil."
  [m newmap]
  (into m (filter #((complement nil?) (second %)) newmap)))


(declare get-id-by-name)

(defn uri-for-entity-type  
  "Returns the proper GET uri given the katello entity type (a
  keyword, eg. :environment). May require some vars be bound, for
  example, to get an environment from the API an org must be set. See
  with-* macros in this namespace."
  [entity-type]

  ;; url-types should be ordered from most stringent reqs to least -
  ;; eg, an api call on an env is more stringent than one on an org.
  
  (let [url-types {[:template :system] {:reqs [#'*env-id*]
                                        :fmt "api/environments/%s/%s"}
                   [:product] {:reqs [#'*env-id*]
                               :fmt "/api/environments/%s/%s"}
                   [:package :erratum] {:reqs [#'*repo-id*]
                                        :fmt "/api/repositories/%s/%s"}
                   
                   
                   [:changeset :repository] {:reqs [#'*session-org* #'*env-id*]
                                             :fmt "api/organizations/%s/environments/%s/%s"}
                   
                   [:repository] {:reqs [#'*session-org* #'*product-id*]
                                  :fmt "/api/organizations/%s/products/%s/%s"}
                   [:environment :provider :system] {:reqs [#'*session-org*]
                                                     :fmt "api/organizations/%s/%s"}
                   [:organization :user] {:reqs []
                                          :fmt "api/%s"}}
                    
        matching-type (filter (comp (partial some (hash-set entity-type)) key) url-types)
        and-matching-reqs (filter (comp (partial every? deref) :reqs val) matching-type)]
    (if (empty? and-matching-reqs)
      (throw (IllegalArgumentException.
              (format "%s are required for entity type %s."
                      (->> matching-type
                           vals
                           (map :reqs)
                           (interpose " or ")
                           (apply str))
                      
                      (name entity-type))))
      
      (let [{:keys [reqs fmt]} (-> and-matching-reqs first val)]
        (apply format fmt (conj (vec (map deref reqs)) (-> entity-type name pluralize)))))))

(defn all-entities
  "Returns a list of all the entities of the given entity-type. If
   that entity type is part of an org, or environment or product,
   those vars must be bound (see with-* macros)"
  [entity-type]
  (-> entity-type uri-for-entity-type rest/api-url rest/http-get))

(defn get-by-name [entity-type entity-name]
  (rest/http-get (rest/api-url (uri-for-entity-type entity-type))
            {:query-params {:name entity-name}}))

(defn get-by-id [entity-type entity-id]
  (rest/http-get (rest/api-url "api/" (-> entity-type name pluralize) (str "/" entity-id))))

(defn get-id-by-name [entity-type entity-name]
  (let [all (get-by-name entity-type entity-name)
        ct (count all)]
    (cond 
     (< ct 1) (throw
               (IllegalArgumentException.
                (format "%d matches for %s named %s, expected 1."
                        ct (name entity-type)
                        entity-name)))
     (= ct 1) (-> all first :id)
      
     :else (first (map :id (get (index all [:name])
                                {:name entity-name}))))))

(defmacro with-env
  "Executes body and makes any included katello api calls using the
   given environment name (it's id will be looked up before executing
   body)."
  [env-name & body]
  `(binding [*env-id* (get-id-by-name :environment ~env-name)]
     (do ~@body)))

(defmacro with-repo
  "Executes body and makes any included katello api calls using the
   given repository name (it's id will be looked up before executing
   body)."
  [repo-name & body]
  `(binding [*repo-id* (get-id-by-name :repository ~repo-name)]
     (do ~@body)))

(defn create-provider [name & [{:keys [description]}]]
  (rest/http-post (rest/api-url "api/providers")
             {:body {:organization_id *session-org*
                     :provider  {:name name
                                 :description description
                                 :provider_type "Custom"}}}))

(defn create-environment [name {:keys [description prior-env] :or {description "" prior-env katello/library}}]
  (rest/http-post (rest/api-url (uri-for-entity-type :environment))
             {:body {:environment (assoc-if-set
                                   {:name name}
                                   {:description description
                                    :prior (and prior-env
                                                (get-id-by-name :environment prior-env))})}}))

(defn delete-environment [name]
  (rest/http-delete (rest/api-url (uri-for-entity-type :environment) "/" name)))

(defn ensure-env-exist
  "If an environment with the given name and prior environment doesn't
   exist, create it."
  [name {:keys [prior]}]
  (locking (keyword *session-org*)  ;;lock on org name to prevent race condition
    (if-not (some #{name}
                  (map :name (all-entities :environment)))
      (create-environment name {:prior-env prior}))))

(defn create-env-chain [envs]
  (doseq [[prior curr] (chain envs)]
    (ensure-env-exist curr {:prior prior})))

(defn create-product [name {:keys [provider-name description]}]
  (rest/http-post (rest/api-url "api/providers/" (get-id-by-name :provider provider-name) "/product_create/")
             {:body {:product (assoc-if-set {:name name}
                                            {:description description})}}))

(defn create-repo [name {:keys [product-name url]}]
  (rest/http-post (rest/api-url "api/repositories/")
             {:body {:organization_id *session-org*
                     :product_id  (get-id-by-name :product product-name)
                     :name name
                     :url url}}))

(defn create-organization [name & [{:keys [description]}]]
  (rest/http-post (rest/api-url (uri-for-entity-type :organization))
             {:body {:name name
                     :description description}}))




(defn create-system [name {:keys [facts]}]
  (rest/http-post (rest/api-url "api/environments/" *env-id* "/consumers")
             {:body {:name name
                     :cp_type "system"
                     :facts facts}}))

(defn create-changeset
  "Creates a changeset. type defaults to 'PROMOTION', can also be
   'DELETION'."
  [name & [{:keys [type]}]]
  (rest/http-post (rest/api-url (uri-for-entity-type :changeset))
             {:body {:changeset {:name name
                                 :type (or type "PROMOTION")}}}))

(defn add-to-changeset [changeset-name entity-type entity]
  (rest/http-post (rest/api-url "api/changesets/" (get-id-by-name :changeset changeset-name) "/" 
                      (-> entity-type name pluralize))
             {:body entity}))

(defn promote-changeset
  "Promotes a changeset, polls the API until the promotion completes,
   and returns the changeset. If the timeout is hit before the
   promotion completes, throws an exception."
  [changeset-name]
  (let [id (get-id-by-name :changeset changeset-name)]
    (locking #'katello.conf/promotion-deletion-lock
      (rest/http-post (rest/api-url "api/changesets/" id "/promote"))
      (loop-with-timeout (* 20 60 1000) [cs {}]
        (let [state (:state cs)]
          (case state
            "promoted" cs
            "failed" (throw+ {:type :failed-promotion :response cs})    
            (do (Thread/sleep 5000)
                (recur (get-by-id :changeset id)))))))))

(defn promote
  "Does a promotion of the given content (creates a changeset, adds
   the content, and promotes it. Content should match the JSON format
   that the API expects. currently like {:product_id '1234567890'}"
  [content]
  (let [cs-name (uniqueify "api-changeset")]
    (create-changeset cs-name)
    (doseq [[ent-type ents] content
            ent ents]
      (add-to-changeset cs-name (singularize ent-type) ent))
    (promote-changeset cs-name)))

(defn create-template [{:keys [name description]}]
  (rest/http-post (rest/api-url "api/templates/")
             {:body {:template {:name name
                                :description description}
                     :environment_id *env-id*}}))

(defn add-to-template [template-name content]
  (comment "content like " {:repositories [{:product "myprod" :name "blah"}]})
  (doseq [[content-type items] content item items]
    (rest/http-post (rest/api-url "api/templates/" (get-id-by-name :template template-name) "/" (name content-type))
               {:body {:id (with-bindings
                             (case content-type
                               :repositories {#'*product-id* (get-id-by-name :product
                                                                             (:product item))}
                               {})
                             (get-id-by-name (singularize content-type) (:name item)))}})))

(defn create-user [username {:keys [password email disabled]}]
  (rest/http-post (rest/api-url (uri-for-entity-type :user))
             {:body {:username username
                     :password password
                     :email email
                     :disabled (or disabled false)}}))

(defn system-available-pools [system-name]
  (let [sysid  (-> (get-by-name :system system-name) first :uuid)]
    (:pools (rest/http-get (rest/api-url (format "api/systems/%s/pools" sysid))))))

(defn upload-manifest [file-name repo-url]
  (let [prov-id (get-id-by-name :provider "Red Hat")]
    (list
     (rest/http-put (rest/api-url "/api/providers/" prov-id) {:body {:provider {:repository_url repo-url}}})  
     (rest/http-post (rest/api-url "/api/providers/" prov-id "/import_manifest")
                {:multipart [{:name "import"
                              :content (clojure.java.io/file file-name)
                              :mime-type "application/zip"
                              :encoding "UTF-8"}]}))))

(defn sync-repo [repo-name & [timeout-ms]]
  (let [url (->> repo-name
               (get-id-by-name :repository)
               (format "/api/repositories/%s/sync")
               rest/api-url)]
    (rest/http-post url) 
    (loop-with-timeout (or timeout-ms 180000) [sync-info {}] 
      (Thread/sleep 15000)
      (if (-> sync-info :state (= "finished"))
        sync-info
        (recur (rest/http-get url))))))

(def get-version-from-server
  (memoize
    (fn [url]
      (try
        (rest/http-get url)
        (catch Exception e {:name "unknown"
                            :version "unknown"
                            :exception e})))))

(def get-version
  (fn [] (get-version-from-server (rest/api-url "/api/version"))))

(defn is-headpin? []
  (-> (get-version) :name (= "Headpin")))

(def is-katello? (complement is-headpin?))

(defmacro when-katello [& body]
  `(when (is-katello?) ~@body))

(defmacro when-headpin [& body]
  `(when (is-headpin?) ~@body))

(defn katello-only
  "A function you can call from :blockers of any test so it will skip
   if run against a non-katello (eg SAM or headpin) deployment"
  [_]
  (if (->> (get-version) :name (= "Headpin"))
    ["This test is for Katello based deployments only and this is a headpin-based server."] []))
