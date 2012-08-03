(ns katello.api-tasks
  (:require [katello.rest :as rest])
  (:use [katello.conf :only [config *session-user* *session-password*]]
        slingshot.slingshot
        [inflections.core :only [pluralize singularize]]
        [com.redhat.qe.auto.selenium.selenium :only [loop-with-timeout]]
        [katello.tasks :only [uniqueify library promotion-lock chain-envs]]))

(def ^:dynamic *user* nil)
(def ^:dynamic *password* nil)
(def ^:dynamic *org* nil)
(def ^:dynamic *env-id* nil)
(def ^:dynamic *product-id* nil)

(defmacro with-creds
  "Execute body and makes any included katello api calls with the
   given user and password."
  [user password & body]
  `(binding [*user* ~user
             *password* ~password]
     (do 
       ~@body)))

(defmacro with-admin-creds
  "Executes body and makes any included katello api calls using the
  admin user and password (which defaults to admin/admin)"
  [& body]
  `(binding [*user* *session-user*
             *password* *session-password*]
     (do ~@body)))

(defmacro with-org
  "Executes body and makes any included katello api calls using the
  given organization."
  [org & body]
  `(binding [*org* ~org]
     (do 
       ~@body)))

(defmacro with-admin-org
  "Executes body and makes any included katello api calls using the
  admin organization (defaults to ACME_Corporation)."
  [& body]
  `(binding [*org* (@config :admin-org)]
     (do ~@body)))

(defmacro with-admin
  "Executes body and makes any included katello api calls using the
  admin user, password, and organization."
  [& body]
  `(binding [*user* *session-user*
             *password* *session-password*
             *org* (@config :admin-org)]
     (do ~@body)))

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
                   [:environment :product :provider :system] {:reqs [#'*org*]
                                                              :fmt "api/organizations/%s/%s"}
                   [:changeset] {:reqs [#'*org* #'*env-id*]
                                 :fmt "api/organizations/%s/environments/%s/%s"}
                   [:template] {:reqs [#'*env-id*]
                                :fmt "api/environments/%s/templates"}
                   [:repository] {:reqs [#'*org* #'*product-id*]
                                  :fmt "/api/organizations/%s/products/%s/repositories"}} 
        {:keys [reqs fmt]} (->> url-types
                              keys
                              (drop-while (complement #(some #{entity-type} %)))
                              first
                              url-types)
        unsat (filter #(-> % deref nil?) reqs)]
    (if-not (empty? unsat)
      (throw (IllegalArgumentException.
              (format "%s are required for entity type %s."
                      (pr-str (map #(-> % meta :name) reqs)) (name entity-type)))))
    (apply format fmt (conj (vec (map deref reqs)) (-> entity-type name pluralize)))))

(defn all-entities
  "Returns a list of all the entities of the given entity-type. If
   that entity type is part of an org, or environment or product,
   those vars must be bound (see with-* macros)"
  [entity-type]
  (rest/with-client-auth *user* *password* 
    (rest/get (api-url (uri-for-entity-type entity-type)))))

(defn get-by-name [entity-type entity-name]
  (rest/with-client-auth *user* *password* 
                         (rest/get-with-params (api-url (uri-for-entity-type entity-type)) {:name entity-name})))

(defn get-by-id [entity-type entity-id]
  (rest/with-client-auth *user* *password* 
                         (rest/get (api-url "api/" (-> entity-type name pluralize) (str "/" entity-id)))))

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

(defn create-provider [name & [{:keys [description]}]]
  (rest/with-client-auth *user* *password*
    (rest/post (api-url "api/providers")
               {:organization_id *org*
                :provider  {:name name
                            :description description
                            :provider_type "Custom"}})))

(defn create-environment [name {:keys [description prior-env] :or {description "" prior-env library}}]
  (rest/with-client-auth *user* *password*
    (rest/post (api-url (uri-for-entity-type :environment))
               {:environment (assoc-if-set
                              {:name name}
                              {:description description
                               :prior (and prior-env
                                           (get-id-by-name :environment prior-env))})})))

(defn delete-environment [name]
  (rest/with-client-auth *user* *password*
                         (rest/delete (api-url (uri-for-entity-type :environment) "/" name))))

(defn ensure-env-exist
  "If an environment with the given name and prior environment doesn't
   exist, create it."
  [name {:keys [prior]}]
  (locking (keyword *org*)  ;;lock on org name to prevent race condition
    (if-not (some #{name}
                  (map :name (all-entities :environment)))
      (create-environment name {:prior-env prior}))))

(defn create-env-chain [envs]
  (doseq [[prior curr] (chain-envs envs)]
    (ensure-env-exist curr {:prior prior})))

(defn create-product [name {:keys [provider-name description]}]
  (rest/with-client-auth *user* *password* 
    (rest/post (api-url "api/providers/" (get-id-by-name :provider provider-name) "/product_create/")
               {:product (assoc-if-set {:name name}
                                       {:description description})})))

(defn create-repo [name {:keys [product-name url]}]
  (rest/with-client-auth *user* *password* 
    (rest/post (api-url "api/repositories/")
               {:organization_id *org*
                :product_id  (get-id-by-name :product product-name)
                :name name
                :url url})))

(defn create-organization [name & [{:keys [description]}]]
  (rest/with-client-auth *user* *password* 
    (rest/post (api-url (uri-for-entity-type :organization))
               {:name name
                :description description})))


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
  (rest/with-client-auth *user* *password*
    (rest/post (api-url "api/environments/" *env-id* "/consumers")
               {:name name
                :cp_type "system"
                :facts facts})))

(defn create-changeset [name]
  (rest/with-client-auth *user* *password*
    (rest/post (api-url (uri-for-entity-type :changeset)) {:changeset {:name name}})))

(defn add-to-changeset [changeset-name entity-type entity]
  (rest/with-client-auth *user* *password*
    (rest/post (api-url "api/changesets/" (get-id-by-name :changeset changeset-name) "/" 
                        (-> entity-type name pluralize))
               entity)))

(defn promote-changeset
  "Promotes a changeset, polls the API until the promotion completes,
   and returns the changeset. If the timeout is hit before the
   promotion completes, throws an exception."
  [changeset-name]
  (let [id (get-id-by-name :changeset changeset-name)]
    (locking #'promotion-lock
      (rest/with-client-auth *user* *password* (rest/post (api-url "api/changesets/" id "/promote") nil))
      (loop-with-timeout 180000 [cs {}]
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
  (rest/with-client-auth *user* *password* 
                         (rest/post (api-url "api/templates/") {:template {:name name :description description} :environment_id *env-id*})))

(defn add-to-template [template-name content]
  (comment "content like " {:repositories [{:product "myprod" :name "blah"}]})
  (doseq [[content-type items] content item items]
    (rest/with-client-auth *user* *password*
      (rest/post (api-url "api/templates/" (get-id-by-name :template template-name) "/" (name content-type))
                 {:id (with-bindings (case content-type
                                       :repositories {#'*product-id* (get-id-by-name :product
                                                                                     (:product item))}
                                       {})
                        (get-id-by-name (singularize content-type) (:name item)))}))))

(defn create-user [username {:keys [password email disabled]}]
  (rest/with-client-auth *user* *password* 
    (rest/post (api-url (uri-for-entity-type :user))
               {:username username
                :password password
                :email email
                :disabled (or disabled false)})))

(defn system-available-pools [system-name]
  (let [sysid  (-> (get-by-name :system system-name) first :uuid)]
    (:pools (rest/with-client-auth *user* *password* 
              (rest/get (api-url (format "api/systems/%s/pools" sysid)))))))

(defn upload-manifest [file-name repo-url]
  (let [prov-id (get-id-by-name :provider "Red Hat")]
    (rest/with-client-auth *user* *password*    
      (rest/put (api-url "/api/providers/" prov-id) {:provider {:repository_url repo-url}})  
      (rest/post-multipart
       (api-url "/api/providers/" prov-id "/import_manifest")
       [{:type :string
         :name "Filename"
         :value file-name
         :charset "UTF-8"}
        {:type :file
         :name file-name
         :file (clojure.java.io/file file-name)
         :mime-type "application/zip"
         :charset "UTF-8"}]))))

(defn sync-repo [repo-name & [timeout-ms]]
  (let [url (->> repo-name
               (get-id-by-name :repository)
               (format "/api/repositories/%s/sync")
               api-url)]
    (rest/with-client-auth *user* *password* 
      (rest/post url {}) 
      (loop-with-timeout (or timeout-ms 180000) [sync-info {}] 
        (Thread/sleep 15000)
        (if (-> sync-info :state (= "finished"))
          sync-info
          (recur (rest/get url)))))))

(def get-server-version
  (memoize
   (fn [url]
     (rest/with-client-auth *user* *password*
                            (rest/get url)))))

(defn get-version []
  (try
   (get-server-version (api-url "/api/version"))
   (catch Exception _ {:name "unknown" :version "unknown"})))

(defn is-headpin? []
  (-> (get-version) :name (= "Headpin") with-admin-creds))

(def is-katello? (complement is-headpin?))

(defmacro when-katello [& body]
  `(when (is-katello?) ~@body))

(defmacro when-headpin [& body]
  `(when (is-headpin?) ~@body))

(defn katello-only
  "A function you can call from :blockers of any test so it will skip
   if run against a non-katello (eg SAM or headpin) deployment"
  [_]
  (if (->> (get-version) :name (= "Headpin") with-admin-creds)
    ["This test is for Katello based deployments only and this is aheadpin-based server."] []))
