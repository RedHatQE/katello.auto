(ns katello.systems
  (:require [clj-webdriver.taxi :as browser]
            [webdriver :as wd]
            [clj-webdriver.core :as action]
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

(wd/template-fns
 {subscription-available-checkbox "//div[@id='panel-frame']//table[@id='subscribeTable']//td[contains(normalize-space(.),'%s')]//input[@type='checkbox']"
  subscription-current-checkbox   "//div[@id='panel-frame']//table[@id='unsubscribeTable']//td[contains(normalize-space(.),'%s')]//input[@type='checkbox']"
  checkbox                        "//input[@class='system_checkbox' and @type='checkbox' and parent::td[normalize-space(.)='%s']]"
  sysgroup-checkbox               "//input[@title='%s']"
  check-selected-env              "//span[@class='checkbox_holder']/input[@class='node_select' and @data-node_name='%s']"
  select-sysgroup-checkbox        "//input[contains(@title,'%s') and @name='multiselect_system_group']"
  activation-key-link             (ui/link "%s")
  env-select                      (ui/link "%s")
  select-system                   "//td[@class='ng-scope']/a[contains(text(), '%s')]"
  select-system-checkbox          "//td[@class='ng-scope']/a[contains(text(), '%s')]/parent::td/preceding-sibling::td[@class='row-select']/input[@ng-model='system.selected']"
  remove-package                  "//td[contains(text(), '%s')]/following::td/i[@ng-click='table.removePackage(package)']"
  get-errata                      "//tr[@id='errata_%s']/td[@class='one-line-ellipsis']"
  package-select                  "//input[@id='package_%s']"
  package-action-status           "//input[@id='package_%s']/following::td[@class='package_action_status']"
  get-filtered-package            "//input[@id='package_%s']/following::td[@class='package_name']"
  environment-checkbox            "//input[@class='node_select' and @type='checkbox' and @data-node_name='%s']"
  system-detail-textbox           "//label[contains(.,'%s')]/../following-sibling::*[1]"
  system-fact-textbox             "//td[contains(.,'%s')]/./following-sibling::*[1]"
  system-fact-group-expand        "//tr[@id='%s']/td/span"
  existing-key-value-field        "custom_info[%s]"
  remove-custom-info-button       "//input[@data-id='custom_info_%s']"})

(ui/defelements :katello.deployment/any []
  {::name-text                   "system[name]"
   ::content-view-select         {:name "system[content_view_id]"}
   ::expand-env-widget           "path-collapsed"
   ::remove                      "//button[contains(text(), 'Remove System')]"
   ::confirmation-yes            "//button[normalize-space(.)='Yes']"
   ::confirmation-no             "//button[normalize-space(.)='No']"
   ::bulk-action                 "//div[@class='nutupane-actions fr']/button[contains (.,'Bulk Actions')]"
   ::multi-remove                (ui/link "Remove System(s)")
   ::confirm-yes                 "//input[@value='Yes']"
   ::select-sysgrp               "//div[@class='alch-edit']/div[@ng-click='edit()']"
   ::add-sysgrp                  "//button[@ng-click='add()']"
   ::confirm-to-no               "xpath=(//button[@type='button'])[3]"
   ::total-sys-count             "total_items_count"
   ::interface-addr              "//td[@class='interface_name' and contains(., 'eth')]//following-sibling::td"
   ::system-groups               {:xpath (ui/third-level-link "systems_system_groups")}
   ::add-group-form              "//form[@id='add_group_form']/button"
   ::add-group                    "//input[@id='add_groups']"

   ;;new system form
   ::sockets-icon                "//fieldset[descendant::input[@id='system_sockets']]//i"
   ::ram-icon                    "//fieldset[descendant::input[@id='system_memory']]//i"
   
   ;;content  
   ::packages                    "//nav[@class='details-navigation']//li/a[contains (text(), 'Packages')]"    
   ::events-link                 "//nav[@class='details-navigation']//li/a[contains (text(), 'Events')]"
   ::errata-link                 "//nav[@class='details-navigation']//li/a[contains (text(), 'Errata')]"
   ::package-action              "//select[@ng-model='packageAction.actionType']"
   ::perform-action              "//input[@name='Perform']"
   ::input-package-name          "//input[@ng-model='packageAction.term']"
   ::filter-package              "//input[@ng-model='currentPackagesTable.filter']"
   ::update-all                  "//button[@ng-click='updateAll()']"
   ::filter-errata               "//input[@ng-model='errataTable.errataFilterTerm']"
  
  
   ;;system-edit details
   ::details                     (ui/link "Details")
   ::name-text-edit              "//div[@alch-edit-text='system.name']//span[@class='fr']/i[@ng-hide='editMode || readonly']"
   ::description-text-edit       "//div[@alch-edit-textarea='system.description']//span[@class='fr']/i[@ng-hide='editMode || readonly']"
   ::save-button                 "//button[@ng-click='save()']"
   ::cancel-button               "//button[@ng-click='cancel()']"
   ::get-selected-env            "//div[@id='path_select_system_details_path_selector']//label[@class='active']//div"
   
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
   ::subscriptions               "//nav[@class='details-navigation']//li/a[contains (text(), 'Subscriptions')]"})  

;; Nav

(nav/defpages :katello.deployment/any katello.menu
  [::page
   [::named-page (fn [system] (nav/go-to-system system))
    [::details-page (nav/browser-fn (click ::details))
    [::subscriptions-page (nav/browser-fn (click ::subscriptions))]
    [::packages-page (nav/browser-fn (click ::packages))
    [::errata-page (nav/browser-fn (click ::errata-link))]]]]])

;; Tasks
(defn- create "Create a system from UI"
  []
  ;;use rest 
  )

(defn- delete "Deletes the selected system."
  [system]
  (nav/go-to system)
  (browser/click ::remove)
  (browser/click ::confirmation-yes))

(defn- select-multisys
  [systems]
  (doseq [system systems]
    (browser/click (select-system-checkbox system))))

(defn multi-delete "Delete multiple systems at once."
  [systems]
  (nav/go-to ::page (first systems))
  (select-multisys systems)
  (browser/click ::bulk-action)
  (browser/click ::remove)
  (browser/click ::confirmation-yes)
  (browser/refresh))

(defn add-bulk-sys-to-sysgrp 
  "Adding systems to system group in bulk by pressing ctrl, from right-pane of system tab."
  [systems group] 
  (select-multisys systems)
  (browser/click ::bulk-action)
  (wd/->browser (click ::select-sysgrp)
                (click (-> group :name sysgroup-checkbox))
                (click ::add-sysgrp)
                (click ::confirmation-yes)))

(defn- add-sys-to-sysgrp
  "Adding sys to sysgroup from right pane"
  [system group-name]
  (nav/go-to system)
  (browser/click ::system-groups)
  (browser/click ::add-group-form)
  (if (browser/exists?  (select-sysgroup-checkbox group-name))
    (do
      (browser/click (select-sysgroup-checkbox group-name))
      (browser/click ::add-group)
      (notification/success-type :sys-add-sysgrps))
    (throw+ {:type ::selected-sys-group-is-unavailable 
             :msg "Selected sys-group is not available to add more system as limit already exceeds"})))

(defn- set-environment "select a new environment for a system"
  [new-environment]
  {:pre [(not-empty new-environment)]} 
  (wd/->browser (click ::environment)
                (click (environment-checkbox new-environment))
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
                           (browser/click (checkbox-fn (:name item))))
                         (browser/click submit)
                         (notification/success-type :sys-update-subscriptions)))]
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
      (let [x {}] (browser/click ::packages-link))
      ))
  (let [ks (list :packages :package-groups)]
    (when (some seq (mapcat #(select-keys % ks) (list to-add to-remove)))
      (browser/click ::packages-link))))

(defn- edit-system-details [{:keys [name description location release-version]}]
  (common/in-place-edit {::name-text-edit name
                         ::description-text-edit description
                         ::location-text-edit location
                         ::release-version-select release-version}))

(defn- update-custom-info [to-add to-remove]
  (browser/click ::custom-info)
  (doseq [[k v] to-add]
    (if (and to-remove (to-remove k)) ;;if also in the remove, it's an update
      (do (common/in-place-edit {(existing-key-value-field k) v}))
      (do (browser/input-text ::key-name k)
          (browser/input-text ::key-value v)
          #_(browser keyUp ::key-name "w") ;; TODO: composite actions fixme
          (browser/click ::create-custom-info))))
  ;; process removes
  (doseq [[k _] (apply dissoc to-remove (keys to-add))]
    (browser/click (remove-custom-info-button k))))

(defn- update
  "Edits the properties of the given system. Optionally specify a new
  name, a new description, and a new location."
  [system updated]
  (let [[to-remove {:keys [name description location release-version
                            service-level auto-attach env]
             :as to-add} _] (data/diff system updated)]
    
    (when (some not-empty (list to-remove to-add))
      (nav/go-to ::details-page system)
      (wd/move-to (browser/element ::name-text-edit))
      (edit-system-details to-add)
      (when env (set-environment (:name env)))

      (when (or (:custom-info to-add) (:custom-info to-remove) )
        (update-custom-info (:custom-info to-add) (:custom-info to-remove)))

      (add-remove-content to-add to-remove)
      
      (let [added-products (:products to-add) 
            removed-products (:products to-remove) ]
        (when (some #(not (nil? %)) (list added-products removed-products
                                          service-level auto-attach))
          (browser/click ::subscriptions)
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

   rest/CRUD (let [headpin-url (partial rest/url-maker [["api/organizations/%s/systems" [#'kt/org]]])
                  katello-url (partial rest/url-maker [["api/environments/%s/systems" [#'kt/env]]])
                  id-url (partial rest/url-maker [["api/systems/%s" [identity]]])]
              {:id :uuid
               :query (fn [sys]
                        (rest/query-by-name 
                          (if (rest/is-katello?) 
                           katello-url headpin-url) sys))
               :read (partial rest/read-impl id-url)
               :create (fn [sys]
                         (merge sys (rest/http-post 
                                      (if (rest/is-katello?) 
                                        (katello-url sys) 
                                        (headpin-url sys))
                                      {:body (assoc (select-keys sys [:name :facts])
                                       :type "system")})))})
  
  tasks/Uniqueable {:uniques #(for [s (tasks/timestamps)]
                                (assoc (tasks/stamp-entity %1 s)
                                  :facts (if-let [f (:facts %1)]
                                           f
                                           (random-facts))))}
  
  nav/Destination {:go-to (partial nav/go-to ::named-page)})


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
  (nav/go-to ::details-page system)
  (browser/text ::get-selected-env))

(defn get-ip-addr
  [system]
  (nav/go-to ::details-page system)
  (browser/text ::interface-addr))

(defn get-details [system]
  (nav/go-to ::details-page system)
  (let [headpin-details ["ID" "UUID" "Hostname" "Interfaces" "Name" "Description" "OS" "Release" "Release Version"
                         "Arch" "RAM (GB)" "Sockets" "Location" "Checked In" "Registered" "Last Booted"
                         "Activation Key" "System Type" "Host"]
        katello-details (conj headpin-details "Environment")
        details (if (rest/is-katello?) katello-details headpin-details)]
    (zipmap details
            (doall (for [detail details]
                     (browser/text (system-detail-textbox detail)))))))

(defn get-facts [system]
  (nav/go-to ::facts-page system)
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
                     (browser/text (system-fact-textbox fact)))))))

(defn expand-collapse-facts-group
  [system]
  "Expand/collapse group of selected system's facts"
  (nav/go-to ::facts-page system)
  (let [groups ["cpu" "distribution" "dmi" "lscpu" "memory" "net" "network" "system" "uname" "virt"]]
    (doseq [group groups] ;;To expand
      (when (browser/exists?  (system-fact-group-expand group))
        (browser/click (system-fact-group-expand group))))
    (doseq [group groups] ;;To collapse
      (browser/click (system-fact-group-expand group)))))


(defn check-package-status
  [&[timeout-ms]]
  (wd/loop-with-timeout (or timeout-ms (* 20 60 1000))[current-status ""]
                         (case current-status
                           "Add Package Complete" current-status
                           "Add Package Group Complete" current-status
                           "Remove Package Complete" current-status
                           "Remove Package Group Complete" current-status
                           "Add Package Error" (throw+ {:type ::package-install-failed :msg "Add Package Error"})
                           "Add Package Group Error" (throw+ {:type ::package-group-install-failed :msg "Add Package Group Error"})
                           "Remove Package Error" (throw+ {:type ::package-remove-failed :msg "Remove Package Error"})
                           "Remove Package Group Error" (throw+ {:type ::remove-package-group-failed :msg "Remove Package Group Error"})              
                           (do (Thread/sleep 2000)
                             (recur (browser/text ::pkg-install-status))))))

(defn check-pkg-update-status
  "Function to test selected package status while updating it"
  [package &[timeout-ms]]
  (wd/loop-with-timeout (or timeout-ms (* 20 60 1000))[current-status ""]
                         (case current-status
                           "Update Package Complete" current-status
                           "Remove Package Complete" current-status
                           "Update Package Error" (throw+ {:type ::update-package-failed :msg "Update Package Error"})
                           "Remove Package Error" (throw+ {:type ::package-remove-failed :msg "Remove Package Error"})
                           (do (Thread/sleep 2000)
                             (recur (browser/text (package-action-status package)))))))

(defn package-action "Install/remove/update package/package-group on selected system "
  [system {:keys [package pkg-action]}]
  (nav/go-to ::packages-page system)
  (browser/select-by-text ::package-action pkg-action)
  (wd/->browser (input-text ::input-package-name package)
                (click ::perform-action)))

(defn remove-selected-package "Remove a selected package from package-list"
  [system {:keys [package]}]
  (nav/go-to ::packages-page system)
  (wd/->browser (input-text ::filter-package package)
                (click (remove-package package))))