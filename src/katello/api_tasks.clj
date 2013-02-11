(ns katello.api-tasks
  (:require [slingshot.slingshot :refer [throw+]] 
            [inflections.core :refer [pluralize singularize]]
            [com.redhat.qe.auto.selenium.selenium :refer [loop-with-timeout]]
            (katello [rest :as rest] 
                     [conf :refer [config *session-user* *session-password* *session-org*]] 
                     [tasks :refer [uniqueify library promotion-deletion-lock chain-envs]])))

(def ^:dynamic *env-id* nil)
(def ^:dynamic *product-id* nil)
(def ^:dynamic *repo-id* nil)

(defn assoc-if-set
  "Adds to map m just the entries from newmap where the value is not nil."
  [m newmap]
  (into m (filter #((complement nil?) (second %)) newmap)))

(defn api-url [& args]
  (apply str (@config :server-url) "/" args))

(declare get-id-by-name)

(defn uri-for-entity-type  
  "Returns the proper GET uri given the katello entity type (a
  keyword, eg. :environment). May require some vars be bound, for
  example, to get an environment from the API an org must be set. See
  with-* macros in this namespace."
  [entity-type]
  (let [url-types {[:organization :user] {:reqs []
                                          :fmt "api/%s"}
                   [:environment :provider :system] {:reqs [#'*session-org*]
                                                     :fmt "api/organizations/%s/%s"}
                   [:changeset :repository] {:reqs [#'*session-org* #'*env-id*]
                                             :fmt "api/organizations/%s/environments/%s/%s"}
                   [:template] {:reqs [#'*env-id*]
                                :fmt "api/environments/%s/%s"}
                   [:repository] {:reqs [#'*session-org* #'*product-id*]
                                  :fmt "/api/organizations/%s/products/%s/%s"}
                   [:product] {:reqs [#'*env-id*]
                                  :fmt "/api/environments/%s/%s"}
                   [:package :erratum] {:reqs [#'*repo-id*]
                                        :fmt "/api/repositories/%s/%s"}} 
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
  (-> entity-type uri-for-entity-type api-url rest/get))

(defn get-by-name [entity-type entity-name]
  (rest/get (api-url (uri-for-entity-type entity-type))
            {:query-params {:name entity-name}}))

(defn get-by-id [entity-type entity-id]
  (rest/get (api-url "api/" (-> entity-type name pluralize) (str "/" entity-id))))

(defn get-id-by-name [entity-type entity-name]
  (let [all (get-by-name entity-type entity-name)
        ct (count all)]
    (if (not= ct 1)
      (throw (IllegalArgumentException. (format "%d matches for %s named %s, expected 1."
                                                ct (name entity-type) entity-name)))
      (-> all first :id))))

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
  (rest/post (api-url "api/providers")
             {:body {:organization_id *session-org*
                     :provider  {:name name
                                 :description description
                                 :provider_type "Custom"}}}))

(defn create-environment [name {:keys [description prior-env] :or {description "" prior-env library}}]
  (rest/post (api-url (uri-for-entity-type :environment))
             {:body {:environment (assoc-if-set
                                   {:name name}
                                   {:description description
                                    :prior (and prior-env
                                                (get-id-by-name :environment prior-env))})}}))

(defn delete-environment [name]
  (rest/delete (api-url (uri-for-entity-type :environment) "/" name)))

(defn ensure-env-exist
  "If an environment with the given name and prior environment doesn't
   exist, create it."
  [name {:keys [prior]}]
  (locking (keyword *session-org*)  ;;lock on org name to prevent race condition
    (if-not (some #{name}
                  (map :name (all-entities :environment)))
      (create-environment name {:prior-env prior}))))

(defn create-env-chain [envs]
  (doseq [[prior curr] (chain-envs envs)]
    (ensure-env-exist curr {:prior prior})))

(defn create-product [name {:keys [provider-name description]}]
  (rest/post (api-url "api/providers/" (get-id-by-name :provider provider-name) "/product_create/")
             {:body {:product (assoc-if-set {:name name}
                                            {:description description})}}))

(defn create-repo [name {:keys [product-name url]}]
  (rest/post (api-url "api/repositories/")
             {:body {:organization_id *session-org*
                     :product_id  (get-id-by-name :product product-name)
                     :name name
                     :url url}}))

(defn create-organization [name & [{:keys [description]}]]
  (rest/post (api-url (uri-for-entity-type :organization))
             {:body {:name name
                     :description description}}))


(defn random-facts
  "Generate facts about a system - used to register fake systems via
  the api. Some facts are randomized to guarantee uniqueness."
  []
  (let [rand (java.util.Random.)
        rand-255 #(.nextInt rand 255)
        splice (comp (partial apply str) interpose) 
        ip-prefix (splice "." (repeatedly 3 rand-255 ))
        mac  (splice ":" (repeatedly 6 #(format "%02x" (rand-255))))] {
    "dmi.bios.runtime_size" "128 KB"
    "lscpu.cpu_op-mode(s)" "64-bit"
    "uname.sysname" "Linux"
    "distribution.name" "Fedora"
    "dmi.system.family" "Virtual Machine"
    "lscpu.l1d_cache" "32K"
    "dmi.system.product_name" "VirtualBox"
    "dmi.bios.address" "0xe0000"
    "lscpu.stepping" "5"
    "virt.host_type" "virtualbox"
    "lscpu.l2d_cache" "6144K"
    "uname.machine" "x86_64"
    "lscpu.thread(s)_per_core" "1"
    "cpu.cpu_socket(s)" "1"
    "net.interface.eth1.hwaddr" mac
    "lscpu.cpu(s)" "1"
    "uname.version" "#1 SMP Fri Oct 22 15:36:08 UTC 2010"
    "distribution.version" "14"
    "lscpu.architecture" "x86_64"
    "dmi.system.manufacturer" "innotek GmbH"
    "network.ipaddr" (format "%s.4" ip-prefix),
    "system.entitlements_valid" "true"
    "dmi.system.uuid" (.toString (java.util.UUID/randomUUID)),
    "uname.release" "2.6.35.6-48.fc14.x86_64"
    "dmi.system.serial_number" "0"
    "dmi.bios.version" "VirtualBox"
    "cpu.core(s)_per_socket" "1"
    "lscpu.core(s)_per_socket" "1"
    "net.interface.lo.broadcast" "0.0.0.0"
    "memory.swaptotal" "2031612"
    "net.interface.lo.netmask" "255.0.0.0"
    "lscpu.model" "37"
    "lscpu.cpu_mhz" "2825.811"
    "net.interface.eth1.netmask" "255.255.255.0"
    "lscpu.numa_node(s)" "1"
    "net.interface.lo.hwaddr" "00:00:00:00:00:00"
    "uname.nodename" "killing-time.appliedlogic.ca"
    "dmi.bios.vendor" "innotek GmbH"
    "network.hostname" (str "killing-time" (rand-255) ".appliedlogic."
                            (rand-nth ["ca" "org" "com" "edu" "in"])),
    "net.interface.eth1.broadcast" (format "%s.255" ip-prefix),
    "memory.memtotal" "1023052"
    "dmi.system.wake-up_type" "Power Switch"
    "cpu.cpu(s)" "1"
    "virt.is_guest" "true"
    "dmi.system.sku_number" "Not Specified"
    "net.interface.lo.ipaddr" "127.0.0.1"
    "distribution.id" "Laughlin"
    "lscpu.cpu_socket(s)" "1"
    "dmi.system.version" "1.2"
    "dmi.bios.rom_size" "128 KB"
    "lscpu.vendor_id" "GenuineIntel"
    "net.interface.eth1.ipaddr" (format "%s.8" ip-prefix),
    "lscpu.cpu_family" "6"
    "dmi.bios.relase_date" "12/01/2006"
    "lscpu.numa_node0_cpu(s)" "0"
    }))

(defn create-system [name {:keys [facts]}]
  (rest/post (api-url "api/environments/" *env-id* "/consumers")
             {:body {:name name
                     :cp_type "system"
                     :facts facts}}))

(defn create-changeset
  "Creates a changeset. type defaults to 'PROMOTION', can also be
   'DELETION'."
  [name & [{:keys [type]}]]
  (rest/post (api-url (uri-for-entity-type :changeset))
             {:body {:changeset {:name name
                                 :type (or type "PROMOTION")}}}))

(defn add-to-changeset [changeset-name entity-type entity]
  (rest/post (api-url "api/changesets/" (get-id-by-name :changeset changeset-name) "/" 
                      (-> entity-type name pluralize))
             {:body entity}))

(defn promote-changeset
  "Promotes a changeset, polls the API until the promotion completes,
   and returns the changeset. If the timeout is hit before the
   promotion completes, throws an exception."
  [changeset-name]
  (let [id (get-id-by-name :changeset changeset-name)]
    (locking #'promotion-deletion-lock
      (rest/post (api-url "api/changesets/" id "/promote"))
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
  (rest/post (api-url "api/templates/")
             {:body {:template {:name name
                                :description description}
                     :environment_id *env-id*}}))

(defn add-to-template [template-name content]
  (comment "content like " {:repositories [{:product "myprod" :name "blah"}]})
  (doseq [[content-type items] content item items]
    (rest/post (api-url "api/templates/" (get-id-by-name :template template-name) "/" (name content-type))
               {:body {:id (with-bindings
                             (case content-type
                               :repositories {#'*product-id* (get-id-by-name :product
                                                                             (:product item))}
                               {})
                             (get-id-by-name (singularize content-type) (:name item)))}})))

(defn create-user [username {:keys [password email disabled]}]
  (rest/post (api-url (uri-for-entity-type :user))
             {:body {:username username
                     :password password
                     :email email
                     :disabled (or disabled false)}}))

(defn system-available-pools [system-name]
  (let [sysid  (-> (get-by-name :system system-name) first :uuid)]
    (:pools (rest/get (api-url (format "api/systems/%s/pools" sysid))))))

(defn upload-manifest [file-name repo-url]
  (let [prov-id (get-id-by-name :provider "Red Hat")]
    (list
     (rest/put (api-url "/api/providers/" prov-id) {:body {:provider {:repository_url repo-url}}})  
     (rest/post (api-url "/api/providers/" prov-id "/import_manifest")
                {:multipart [{:name "import"
                              :content (clojure.java.io/file file-name)
                              :mime-type "application/zip"
                              :encoding "UTF-8"}]}))))

(defn sync-repo [repo-name & [timeout-ms]]
  (let [url (->> repo-name
               (get-id-by-name :repository)
               (format "/api/repositories/%s/sync")
               api-url)]
    (rest/post url) 
    (loop-with-timeout (or timeout-ms 180000) [sync-info {}] 
      (Thread/sleep 15000)
      (if (-> sync-info :state (= "finished"))
        sync-info
        (recur (rest/get url))))))

(def get-version-from-server
  (memoize
    (fn [url]
      (try
        (rest/get url)
        (catch Exception e {:name "unknown"
                            :version "unknown"
                            :exception e})))))

(def get-version
  (fn [] (get-version-from-server (api-url "/api/version"))))

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
