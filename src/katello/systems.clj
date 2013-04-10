(ns katello.systems
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser ->browser]]
            [clojure.string :refer [blank?]]
            [clojure.data :as data]
            [slingshot.slingshot :refer [throw+]]
            [katello.tasks :refer [expecting-error]]
            [test.assert :as assert]
            [katello :as kt]
            (katello [navigation :as nav]
                     environments
                     [notifications :as notification]
                     [ui :as ui]
                     [ui-common :as common]
                     [rest :as rest]
                     [tasks :as tasks])))

;; Locators

(sel/template-fns
 {subscription-available-checkbox "//div[@id='panel-frame']//table[@id='subscribeTable']//td[contains(normalize-space(.),'%s')]//input[@type='checkbox']"
  subscription-current-checkbox   "//div[@id='panel-frame']//table[@id='unsubscribeTable']//td[contains(normalize-space(.),'%s')]//input[@type='checkbox']"
  checkbox                        "//input[@class='system_checkbox' and @type='checkbox' and parent::td[normalize-space(.)='%s']]"
  sysgroup-checkbox               "//input[@title='%s']"
  select-sysgroup-checkbox        "//input[contains(@title,'%s') and @name='multiselect_system_group']"
  activation-key-link             (ui/link "%s")
  env-select                      (ui/link "%s")
  environment-checkbox            "//input[@class='node_select' and @type='checkbox' and @data-node_name='%s']"
  system-detail-textbox           "//label[contains(.,'%s')]/../following-sibling::*[1]"
  system-fact-textbox             "//td[contains(.,'%s')]/./following-sibling::*[1]"
  system-fact-group-expand        "//tr[@id='%s']/td/span"
  existing-key-value-field        "//div[@name='custom_info[%s]']"
  remove-custom-info-button       "//input[@data-id='custom_info_%s']"})

(ui/deflocators
  {::new                         "new"
   ::create                      "system_submit"
   ::name-text                   "system[name]"
   ::sockets-text                "system[sockets]"
   ::arch-select                 "arch[arch_id]"
   ::system-virtual-type         "system_type_virtualized_virtual"
   ::expand-env-widget           "path-collapsed"
   ::remove                      (ui/link "Remove System")
   ::multi-remove                (ui/link "Remove System(s)")
   ::confirm-yes                 "//input[@value='Yes']"
   ::select-sysgrp               "//button[@type='button']"
   ::add-sysgrp                  "//input[@value='Add']"
   ::confirm-to-yes              "xpath=(//input[@value='Yes'])[4]"
   ::confirm-to-no               "xpath=(//button[@type='button'])[3]"
   ::total-sys-count             "total_items_count"
   ::interface-addr              "xpath=id('interface_table')/x:tbody/x:tr[1]/x:td[2]"
   ::system-groups               (ui/menu-link "systems_system_groups")
   ::add-group-form              "//form[@id='add_group_form']/button"
   ::add-group                    "//input[@id='add_groups']"

   ;;new system form
   ::sockets-icon                "//fieldset[descendant::input[@id='system_sockets']]//i"
   ::ram-icon                    "//fieldset[descendant::input[@id='system_memory']]//i"
   
   ;;content
   ::content-link                (ui/menu-link "system_content")
   ::packages-link               (ui/menu-link "systems_packages")
   ::software-link               (ui/menu-link "system_products")
   ::errata-link                 (ui/menu-link "errata")
   ::add-content		      "add_content"
   ::remove-content              "remove_content" 
   ::package-name                "content_input"
   ::select-package-group        "perform_action_package_groups"
   ::select-package              "perform_action_packages"
   ::pkg-install-status           "//td[@class='package_action_status']/a[@class='subpanel_element']"
   ::add-package-error            (ui/link "Add Package Error")
   ::install-result               "xpath=(//div[@class='grid_7 multiline'])[2]"

   ;;system-edit details
   ::details                     (ui/menu-link "general")
   ::name-text-edit              "system[name]"
   ::description-text-edit       "system[description]"
   ::location-text-edit          "system[location]"
   ::service-level-select        "system[serviceLevel]"
   ::release-version-select      "system[releaseVer]"
   ::environment                 "//div[@id='environment_path_selector']"              
   ::save-environment            "//input[@value='Save']"
   ::edit-sysname                "system_name"
   ::edit-description            "system_description"
   ::save-button                 "//button[@type='submit']"
   ::cancel-button               "//button[@type='cancel']"
   
   ;;system-facts
   ::facts                       (ui/link "Facts")
   ::network-expander            "network"
   ::cpu-expander                "cpu"
   ::uname-expander              "uname"
   ::virt-expander               "virt" 
   ::net-hostname                "//tr[@id='network.hostname']/td[3]"  
   ::cpu-socket	                 "//tr[@id='cpu.cpu_socket(s)']/td[3]" 
   ::machine-arch                "//tr[@id='uname.machine']/td[3]"    
   ::virt-status                 "//tr[@id='virt.is_guest']/td[3]"
   
   ;;custom-info
   ::custom-info                (ui/link "Custom Information")
   ::key-name                   "new_custom_info_keyname"
   ::key-value                  "new_custom_info_value"
   ::create-custom-info         "create_custom_info_button"
   
   ;;subscriptions pane
   ::subscriptions               (ui/menu-link "systems_subscriptions")
   ::subscribe                   "sub_submit"
   ::unsubscribe                 "unsub_submit"})

;; Nav

(nav/defpages (common/pages)
  [::page
   [::new-page [] (browser click ::new)]
   [::named-page [system] (nav/choose-left-pane system)
    [::details-page [] (browser click ::details)
     [::facts-page [] (browser click ::facts)]
     [::custom-info-page [] (browser click ::custom-info)]]
    [::subscriptions-page [] (browser click ::subscriptions)]
    [::content-menu [] (browser mouseOver ::content-link)
     [::content-software-page [] (browser click ::software-link)]
     [::content-packages-page [] (browser click ::packages-link)]
     [::content-errata-page [] (browser click ::errata-link)]]]]
  [::by-environments-page
   [::environment-page [env] (nav/select-environment-widget env)
    [::named-by-environment-page [system] (nav/choose-left-pane system)]]])

;; Tasks

(defn create
  "Creates a system"
  [{:keys [name env sockets system-arch]}]
  (nav/go-to ::new-page {:org (:org env)})
  (sel/fill-ajax-form [::name-text name
                       ::sockets-text sockets
                       ::arch-select (or system-arch "x86_64")
                       (fn [env]
                         (when env (nav/select-environment-widget env))) [env]]
                      ::create)
  (notification/check-for-success {:match-pred (notification/request-type? :sys-create)}))

(defn create-with-details
  "Creates a system"
  [name & [{:keys [sockets system-arch type-is-virtual? env]}]]
  (nav/go-to ::new-page)
  (when (not (nil? env))
    (when (browser isElementPresent ::expand-env-widget)
      (browser click ::expand-env-widget))
    (browser click (env-select env)))
  (when type-is-virtual?
    (browser click ::system-virtual-type))
  (sel/fill-ajax-form {::name-text name
                       ::sockets-text sockets
                       ::arch-select (or system-arch "x86_64")}
                       ::create)
  (notification/check-for-success {:match-pred (notification/request-type? :sys-create)}))

(defn delete "Deletes the selected system."
  [system]
  (nav/go-to system)
  (browser click ::remove)
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success {:match-pred (notification/request-type? :sys-destroy)}))

(defn- select-multisys-with-ctrl 
  [systems]
  (nav/go-to ::page {:org (-> systems first :env :org)})
  (browser controlKeyDown)
  (doseq [system systems]
    (nav/scroll-to-left-pane-item system)
    (nav/choose-left-pane system))
  (browser controlKeyUp))

(defn multi-delete "Delete multiple systems at once."
  [systems]
  (select-multisys-with-ctrl systems)
  (browser click ::multi-remove)
  (browser click ::confirm-yes)
  (notification/check-for-success {:match-pred (notification/request-type? :sys-bulk-destroy)}))

(defn add-bulk-sys-to-sysgrp 
  "Adding systems to system group in bulk by pressing ctrl, from right-pane of system tab."
  [systems group] 
  (select-multisys-with-ctrl systems)
  (->browser (click ::select-sysgrp)
             (click (-> group :name sysgroup-checkbox))
             (click ::add-sysgrp)
             (click ::confirm-to-yes))
  (notification/check-for-success))

(defn add-sys-to-sysgrp
  "Adding sys to sysgroup from right pane"
  [system group-name]
  (nav/go-to ::named-page {:system system})
  (browser click ::system-groups)
  (browser click ::add-group-form)
  (if (browser isElementPresent (select-sysgroup-checkbox group-name))
    (do
      (browser click (select-sysgroup-checkbox group-name))
      (browser click ::add-group)
      (notification/check-for-success))
    (throw+ {:type ::selected-sys-group-is-unavailable 
             :msg "Selected sys-group is not available to add more system as limit already exceeds"})))

(defn edit
  "Edits the properties of the given system. Optionally specify a new
  name, a new description, and a new location."
  [name {:keys [new-name description location release-version]}]
  (nav/go-to ::details-page {:system name})
  (common/in-place-edit {::name-text-edit new-name
                         ::description-text-edit description
                         ::location-text-edit location
                         ::release-version-select release-version}))

(defn- set-environment "select a new environment for a system"
  [new-environment]
  {:pre [(not-empty new-environment)]} 
  (sel/->browser (click ::environment)
                 (check (environment-checkbox new-environment))
                 (click ::save-environment)))

(defn subscribe
  "Subscribes the given system to the products. (products should be a
  list). Can also set the auto-subscribe for a particular SLA.
  auto-subscribe must be either true or false to select a new setting
  for auto-subscribe and SLA. If auto-subscribe is nil, no changes
  will be made."
  [add-products remove-products]
  (let [sub-unsub-fn (fn [content checkbox-fn submit]
                       (when-not (empty? content)
                         (doseq [item content]
                           (browser check (checkbox-fn (:name item))))
                         (browser click submit)
                         (notification/check-for-success
                          {:match-pred (notification/request-type? :sys-update-subscriptions)})) )]
    (sub-unsub-fn add-products subscription-available-checkbox ::subscribe)
    (sub-unsub-fn remove-products subscription-current-checkbox ::unsubscribe)))

(defn- add-remove-content
  "Adds/removes package[-groups] to a system.  Must already be on a system page."
  [to-add to-remove]
  (let [packages-to-add (:packages to-add)
        packages-to-remove (:packages to-remove)
        groups-to-add (:package-groups to-add)
        groups-to-remove (:package-groups to-remove)]
    (when (or packages-to-add packages-to-remove)
      (let [x {}] (browser click ::packages-link))
      ))
  (let [ks (list :packages :package-groups)]
    (when (some seq (mapcat #(select-keys % ks) (list to-add to-remove)))
      (browser click ::packages-link))))

(defn- edit-system-details [{:keys [name description location release-version]}]
  (common/in-place-edit {::name-text-edit name
                         ::description-text-edit description
                         ::location-text-edit location
                         ::release-version-select release-version}))

(defn- update-custom-info [to-add to-remove]
  (browser click ::custom-info)
  (doseq [[k v] to-add]
    (if (to-remove k) ;;if also in the remove, it's an update
      (do (browser click (existing-key-value-field k))
          (sel/fill-ajax-form {(existing-key-value-field k) v} ::save-button)
          (notification/check-for-success))
      (do (browser setText ::key-name k)
          (browser setText ::key-value v)
          (browser click ::create-custom-info)
          (notification/check-for-success))))
  ;; process removes
  (doseq [[k _] (apply dissoc to-remove (keys to-add))]
    (browser click (remove-custom-info-button k))
    (notification/check-for-success)))

(defn update
  "Edits the properties of the given system. Optionally specify a new
  name, a new description, and a new location."
  [system updated]
  (let [[to-remove {:keys [name description location release-version
                            service-level auto-attach env]
             :as to-add} _] (data/diff system updated)]
    
    (when (some not-empty (list to-remove to-add))
      (nav/go-to ::details-page {:system system
                                 :org (-> system :env :org)})
      (edit-system-details to-add)
      (when env (set-environment (:name env)))

      (when (or (:custom-info to-add) (:custom-info to-remove) )
        (update-custom-info (:custom-info to-add) (:custom-info to-remove)))

      (add-remove-content to-add to-remove)
      
      (let [added-products (:products to-add) 
            removed-products (:products to-remove) ]
        (when (some #(not (nil? %)) (list added-products removed-products
                                          service-level auto-attach))
          (browser click ::subscriptions)
          (subscribe added-products removed-products)
          (when (some #(not (nil? %)) (list service-level auto-attach))
            (common/in-place-edit {::service-level-select (format "Auto-attach %s, %s"
                                                                  (if (:auto-attach updated) "On" "Off")
                                                                  (:service-level updated))})))))))

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

(extend katello.System
  ui/CRUD {:create create
           :delete delete
           :update* update}

  rest/CRUD (let [query-url (partial rest/url-maker [["api/environments/%s/systems" [#'kt/env]]
                                                     ["api/organizations/%s/systems" [#'kt/org]]])
                  id-url (partial rest/url-maker [["api/systems/%s" [identity]]])]
              {:id :uuid
               :query (partial rest/query-by-name query-url)
               :read (partial rest/read-impl id-url)
               :create (fn [sys]
                         (merge sys (rest/http-post (query-url sys)
                                               {:body (assoc (select-keys sys [:name :facts])
                                                        :type "system")})))})
  
  tasks/Uniqueable {:uniques #(for [s (tasks/timestamps)]
                                (assoc (tasks/stamp-entity % s) :facts (random-facts)))}
  
  nav/Destination {:go-to (fn [system]
                            (nav/go-to ::named-page {:system system
                                                     :org (-> system :env :org)}))})


(defn api-pools
  "Gets all pools a system is subscribed to"
  [system]
  (->> (rest/http-get (rest/url-maker [["api/systems/%s/pools" [identity]]] system))
       :pools
       (map kt/newPool)))

(defn pool-provides-product [{:keys [name] :as product}
                             {:keys [productName providedProducts] :as pool}]
  (or (= productName name)
      (some #(= (:productName %) name)
            providedProducts)))

(defn pool-id "Fetch subscription pool-id"
  [system product]
  (->> system
       api-pools
       (filter (partial pool-provides-product product))
       first :id))

(defn environment "Get name of current environment of the system"
  [system]
  (nav/go-to ::details-page {:system system})
  (browser getText ::environment))

(defn get-ip-addr
  [system]
  (nav/go-to ::details-page {:system system})
  (browser getText ::interface-addr))

(defn get-details [system]
  (nav/go-to ::details-page {:system system
                             :org (-> system :env :org)})
  (let [details ["ID" "UUID" "Hostname" "Interfaces" "Name" "Description" "OS" "Release" "Release Version"
                 "Arch" "RAM (MB)" "Sockets" "Location" "Environment"
                 "Checked In" "Registered" "Last Booted" "Activation Key"
                 "System Type" "Host"]]
    (zipmap details
            (doall (for [detail details]
                     (browser getText (system-detail-textbox detail)))))))

(defn get-facts [system]
  (nav/go-to ::facts-page {:system system})
  (let [facts ["cpu.core(s)_per_socket" "cpu.cpu(s)" "cpu.cpu_socket(s)" 
               "distribution.id" "distribution.name" "distribution.version"
               "memory.memtotal" "memory.swaptotal"
               "virt.host_type" "virt.is_guest" "virt.uuid"
               "uname.machine" "uname.nodename" "uname.release"
               "uname.sysname" "uname.version" "system.entitlements_valid"
               "network.hostname" "network.ipv4_address" "network.ipv6_address"
               "net.interface.eth0.ipv4_address" "net.interface.eth0.ipv4_broadcast" "net.interface.eth0.ipv4_netmask"
               "net.interface.lo.ipv4_address" "dmi.bios.vendor" "dmi.bios.version" "lscpu.vendor_id" "lscpu.vendor_id"]]      
    (zipmap facts
            (doall (for [fact facts]
                     (browser getText (system-fact-textbox fact)))))))

(defn expand-collapse-facts-group
  [system]
  "Expand/collapse group of selected system's facts"
  (nav/go-to ::facts-page {:system system})
  (let [groups ["cpu" "distribution" "dmi" "lscpu" "memory" "net" "network" "system" "uname" "virt"]]
    (doseq [group groups] ;;To expand
      (when (browser isElementPresent (system-fact-group-expand group))
        (browser click (system-fact-group-expand group))))
    (doseq [group groups] ;;To collapse
      (browser click (system-fact-group-expand group)))))


(defn add-package "Add a package or package group to a system."
  [system {:keys [package package-group]}]
  (nav/go-to ::content-packages-page {:system system})
  (doseq [[items exp-status is-group?] [[package "Add Package Complete" false]
                                        [package-group "Add Package Group Complete" true]]]
    (when items
      (when is-group? (browser click ::select-package-group))
      (sel/->browser (setText ::package-name items)
                     (typeKeys ::package-name items)
                     (click ::add-content))
      (Thread/sleep 50000)
      (when-not (= exp-status
                   (browser getText ::pkg-install-status))
        (throw+ {:type ::package-install-failed :msg "Add Package Error"})))))


(defn remove-package "Remove a package or package group from a system."
  [system {:keys [package package-group]}]
  (nav/go-to ::content-packages-page {:system system})
  (doseq [[items exp-status is-group?] [[package "Remove Package Complete" false]
                                        [package-group "Remove Package Group Complete" true]]]
    (when items
      (when is-group? (browser click ::select-package-group))
      (sel/->browser (setText ::package-name items)
                     (typeKeys ::package-name items)
                     (click ::remove-content))
      (Thread/sleep 50000)
      (assert/is (= exp-status
                    (browser getText ::pkg-install-status))))))




