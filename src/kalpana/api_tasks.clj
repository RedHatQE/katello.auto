(ns kalpana.api-tasks
  (:require [kalpana.rest :as rest]
            [clojure.contrib.logging :as log])
  (:use [kalpana.conf :only [config]]
        [inflections :only [pluralize]]))


(def product-data-url "http://axiom.rdu.redhat.com/git/gitweb.cgi?p=kalpana;a=blob_plain;f=playpen/test-data/products.json;hb=HEAD")

(defn api-url [& args]
  (apply str (@config :server-url) args))

(defn uri-for-entity-type  
  [entity-type & [org-name]]
  (str "api/" (if (some #(= entity-type %) [:environment :provider :product])
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
  (log/info (str "Retrieving all " (-> entity-type name pluralize) "."))
  (rest/get
   (api-url (uri-for-entity-type entity-type org-name))
   {:basic-auth [(@config :admin-user) (@config :admin-password)]}))

(defn get-id-by-name [entity-type entity-name & [org-name]]
  (log/info (str "Getting id for " (name entity-type) " "
                 entity-name (if org-name (str " in org " org-name) "")))
  (or (some (fn [ent] (if (= (:name ent) entity-name)
                    (ent :id)
                    false))
            (all-entities entity-type org-name))
      (throw (RuntimeException. (str "No matches for " (name entity-type) " named " entity-name)))))

(defn create-provider [org-name api-user api-password
                               & {:keys [name description repo-url type]}]
  (log/info (str "Creating provider " name))
  (rest/post
   (api-url (uri-for-entity-type :provider org-name))
   api-user api-password
   {:provider {:name name
               :description description
               :repository_url repo-url
               :provider_type type}}))

(defn create-environment [name org-name api-user api-password & {:keys [description prior-env]}]
  (rest/post
   (api-url (uri-for-entity-type :environment org-name))
   (@config :admin-user) (@config :admin-password)
   {:environment {:name name
                  :description description
                  :organization_id (get-id-by-name :organization org-name)
                  :prior (get-id-by-name :environment prior-env org-name)}}))

(defn delete-environment [org name]
  (rest/delete
   (api-url (uri-for-entity-type :environment (@config :admin-org)) "/" name)
   (@config :admin-user) (@config :admin-password)))

(defn create-product [org name provider-name]
  (let [product (->> (rest/get product-data-url) :products first)
        updated-product (assoc product
                          :name name
                          :id name
                          :href (str "/products/" name))]
    (rest/post
     (api-url "/api/providers/" provider-name "/import_products/")
     (@config :admin-user) (@config :admin-password)
     {:products [updated-product]})))

(defn create-organization [name description]
  (rest/post
   (api-url (uri-for-entity-type :organization))
   (@config :admin-user) (@config :admin-password)
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

(defn create-system [name facts]
  (rest/post (api-url "api/consumers")
             (@config :owner-user) (@config :owner-password)
             {:name name
              :cp_type "system"
              :facts facts}))
