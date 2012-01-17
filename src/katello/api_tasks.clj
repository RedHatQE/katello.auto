(ns katello.api-tasks
  (:require [katello.rest :as rest])
  (:use [katello.conf :only [config]]
        [inflections.core :only [pluralize singularize]]
        [com.redhat.qe.auto.selenium.selenium :only [loop-with-timeout]]
        [katello.tasks :only [uniqueify]]))

(def ^{:dynamic true} *user* nil)
(def ^{:dynamic true} *password* nil)
(def ^{:dynamic true} *org* nil)
(def ^{:dynamic true} *env-id* nil)

(defmacro with-creds
  [user password & body]
  `(binding [*user* ~user
             *password* ~password]
     (do 
       ~@body)))

(defmacro with-admin-creds [& body]
  `(binding [*user* (@config :admin-user)
             *password* (@config :admin-password)]
     (do ~@body)))

(defmacro with-org
  [org & body]
  `(binding [*org* ~org]
     (do 
       ~@body)))

(defmacro with-admin-org [& body]
  `(binding [*org* (@config :admin-org)]
     (do ~@body)))

(defmacro with-admin [& body]
  `(binding [*user* (@config :admin-user)
             *password* (@config :admin-password)
             *org* (@config :admin-org)]
     (do ~@body)))

(defn assoc-if-set [m newmap]
  (into m (filter #((complement nil?) (second %)) newmap)))

(defn api-url [& args]
  (apply str (@config :server-url) args))

(declare get-id-by-name)

(defn uri-for-entity-type  
  [entity-type]
  (let [url-types {[:organization :user] {:reqs []
                                              :fmt "api/%s"}
                   [:environment :product :provider :system] {:reqs [#'*org*]
                                                              :fmt "api/organizations/%s/%s"}
                   [:changeset] {:reqs [#'*org* #'*env-id*]
                                 :fmt "api/organizations/%s/environments/%s/%s"}
                   [:template] {:reqs [#'*env-id*]
                                :fmt "api/environments/%s/templates"}}
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
  "Returns a list of all the entities of the given entity-type.  If
  that entity type is part of an organization, the name of the org
  must also be passed in."
  [entity-type]
  (rest/get
   (api-url (uri-for-entity-type entity-type))
   {:basic-auth [*user* *password*]}))

(defn get-by-name [entity-type entity-name]
  (rest/get (api-url (uri-for-entity-type entity-type))
            {:basic-auth [*user* *password*]
             :query-params {:name entity-name}}))

(defn get-by-id [entity-type entity-id]
  (rest/get (api-url "api/" (-> entity-type name pluralize) (str "/" entity-id))
            {:basic-auth [*user* *password*]}))

(defn get-id-by-name [entity-type entity-name]
  (let [all (get-by-name entity-type entity-name)
        ct (count all)]
    (if (not= ct 1)
      (throw (IllegalArgumentException. (format "%d matches for %s named %s, expected 1."
                                                ct (name entity-type) entity-name)))
      (-> all first :id))))


(defmacro with-env [env-name & body]
  `(binding [*env-id* (get-id-by-name :environment ~env-name)]
     (do ~@body)))

(defn create-provider [name & [{:keys [description]}]]
  (rest/post
   (api-url "api/providers")
   *user* *password*
   {:organization_id *org*
    :provider  {:name name
                :description description
                :provider_type "Custom"}}))

(defn create-environment [name {:keys [description prior-env] :or {description "" prior-env "Locker"}}]
  (rest/post
   (api-url (uri-for-entity-type :environment))
   *user* *password*
   {:environment (assoc-if-set
                  {:name name}
                  {:description description
                   :prior (and prior-env
                               (get-id-by-name :environment prior-env))})}))

(defn delete-environment [name]
  (rest/delete
   (api-url (uri-for-entity-type :environment) "/" name)
   *user* *password*))

(defn ensure-env-exist [name {:keys [prior]}]
  (if-not (some #{name}
                (map :name (all-entities :environment)))
    (create-environment name {:prior-env prior})))

(defn create-product [name {:keys [provider-name description]}]
  (rest/post (api-url "api/providers/" (get-id-by-name :provider provider-name) "/product_create/")
             *user* *password*
             {:product (assoc-if-set {:name name}
                                     {:description description})}))

(defn create-repo [name {:keys [product-name url]}]
  (rest/post (api-url "api/repositories/")
             *user* *password*
             {:product_id  (get-id-by-name :product product-name)
              :name name
              :url url}))

(defn create-organization [name & [{:keys [description]}]]
  (rest/post
   (api-url (uri-for-entity-type :organization))
   *user* *password*
   {:name name
    :description description}))


(defn random-facts []
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
             *user* *password*
             {:name name
              :cp_type "system"
              :facts facts}))

(defn create-changeset [name]
  (rest/post (api-url (uri-for-entity-type :changeset))
             *user* *password*
             {:changeset {:name name}}))

(defn add-to-changeset [changeset-name {:keys [content]}]
  (let [patch-actions [:+products :+packages :+repos :+errata :+templates]
        getfn (fn [kw] (-> kw name (.substring 1) keyword content))]
    (rest/put (api-url "api/changesets/" (get-id-by-name :changeset changeset-name))
              *user* *password*
              {:patch (zipmap patch-actions (map getfn patch-actions))})))

(defn add-to-changeset [changeset-name entity-type entity]
  (rest/post (api-url "api/changesets/" (get-id-by-name :changeset changeset-name) "/" (-> entity-type name pluralize))
             *user* *password*
             entity))

(defn promote-changeset "Returns a future to return the promoted changeset." [changeset-name]
  (let [id (get-id-by-name :changeset changeset-name)]
    (rest/post (api-url "api/changesets/" id "/promote")
                           *user* *password*
                           nil)
    (loop-with-timeout 180000 [cs {}]
      (cond (-> cs :state (= "promoted"))
            cs
            
            :else
            (do (Thread/sleep 5000)
                (recur (get-by-id :changeset id)))))))

(defn promote [content]
  (let [cs-name (uniqueify "api-changeset")]
    (create-changeset cs-name)
    (doseq [[ent-type ents] content
          ent ents]
      (add-to-changeset cs-name (-> ent-type name singularize keyword) ent ))
    (promote-changeset cs-name)))

(defn create-template [{:keys [name description]}]
  (rest/post (api-url (uri-for-entity-type :template))
             *user* *password*
             {:template {:name name
                         :description description}
              :environment_id *env-id*}))

(defn add-to-template [template-name content]
  (doseq [[content-type items] content item items]
    (rest/post (api-url "api/templates/" (get-id-by-name :template template-name) "/"
                        (name content-type))
               *user* *password*
               {:id (get-id-by-name (-> content-type name singularize keyword) item)})))

(defn create-user [username {:keys [password email disabled]}]
  (rest/post (api-url (uri-for-entity-type :user))
             *user* *password*
             {:username username
              :password password
              :email email
              :disabled (or disabled false)}))

(defn system-available-pools [system-name]
  (let [sysid  (-> (get-by-name :system system-name) first :uuid)]
    (:pools (rest/get (api-url (format "api/systems/%s/pools" sysid))
               {:basic-auth [*user* *password*]}))))