(ns katello.api-tasks
  (:require [katello.rest :as rest])
  (:use [katello.conf :only [config]]
        [inflections :only [pluralize]]))

(def ^{:dynamic true} *user* nil)
(def ^{:dynamic true} *password* nil)
(def ^{:dynamic true} *org* nil)

(defmacro with-creds
  [user password & body]
  `(binding [*user* user
             *password* password]
     (do 
       ~@body)))

(defmacro with-admin-creds [& body]
  `(binding [*user* (@config :admin-user)
             *password* (@config :admin-password)]
     (do ~@body)))

(defmacro with-org
  [org & body]
  `(binding [*org* org]
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

(defn uri-for-entity-type  
  [entity-type & [org-name]]
  (str "api/" (if (some #(= entity-type %) [:environment :product :system :provider])
                 (str "organizations/"
                      (or org-name
                          (throw (IllegalArgumentException.
                                  (str "Org name is required for this entity type: "
                                       entity-type))))
                      "/")
                 "")
       (-> entity-type name pluralize)))

(defn all-entities
  "Returns a list of all the entities of the given entity-type.  If
  that entity type is part of an organization, the name of the org
  must also be passed in."
  [entity-type & [org-name]]
  (rest/get
   (api-url (uri-for-entity-type entity-type org-name))
   {:basic-auth [*user* *password*]}))

(comment (defn get [entity-type id-or-name]
   (rest/get (api-url (str "api/" (-> entity-type name pluralize) "/" id-or-name))
             {:basic-auth [*user* *password*]})))

(defn first-matching-entity [])

(defn lookup-by [k v entity-type & [org-name]]
  (or (some (fn [ent] (if (= (k ent) v)
                       ent))
            (all-entities entity-type org-name))
      (throw (RuntimeException. (format "No matches for %s with %s=%s."
                                        (name entity-type) (name k) v)))))

(defn get-id-by-name [entity-type entity-name & [org-name]]
  (:id (lookup-by :name entity-name entity-type org-name)))

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
   (api-url (uri-for-entity-type :environment *org*))
   *user* *password*
   {:environment (assoc-if-set
                  {:name name}
                  {:description description
                   :prior (and prior-env
                               (get-id-by-name :environment prior-env *org*))})}))

(defn delete-environment [name]
  (rest/delete
   (api-url (uri-for-entity-type :environment *org*) "/" name)
   *user* *password*))

(defn ensure-env-exist [name {:keys [prior]}]
  (if-not (some #{name}
                (map :name (all-entities :environment *org*)))
    (create-environment name {:prior-env prior})))

(defn create-product [name {:keys [provider-name description]}]
  (rest/post (api-url "api/providers/" (get-id-by-name :provider provider-name *org*) "/product_create/")
             *user* *password*
             {:product (assoc-if-set {:name name}
                                     {:description description})}))

(defn create-repo [name {:keys [product-name url]}]
  (rest/post (api-url "api/repositories/")
             *user* *password*
             {:product_id  (:id (lookup-by :name product-name :product *org*))
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
        splice (fn [sep coll] (apply str (interpose sep coll)))
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

(defn create-system [name {:keys [env-name facts]}]
  (rest/post (api-url "api/environments/"
                      (str (get-id-by-name :environment env-name *org*)) "/consumers")
             *user* *password*
             {:name name
              :cp_type "system"
              :facts facts}))

(defn create-template [name {:keys [description env-name] }]
  (rest/post (api-url "api/templates/")
             *user* *password*
             {:template {:name name
                         :description description}
              :environment_id (str (get-id-by-name :environment env-name *org*))}))
