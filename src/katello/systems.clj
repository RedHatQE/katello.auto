(ns katello.systems
  (:require [com.redhat.qe.auto.selenium.selenium :as sel]
            [clojure.string :refer [blank?]]
            [test.assert :as assert]
            ui.navigate
            (katello [navigation :as nav]
                     [locators :as locators] 
                     [notifications :as notification]
                     [ui-tasks :as ui])))

;; Locators

(swap! locators/uimap merge
       {:new-system                             "new"
        :create-system                          "system_submit"
        :system-name-text                       "system[name]"
        :system-sockets-text                    "system[sockets]"
        :system-arch-select                     "arch[arch_id]"
        :system-content-select                  "xpath=(//li[@id='content']/a)[2]"
        :system-content-packages                (locators/link "Packages")
        :system-add-content			               "add_content"
        :system-remove-content                  "remove_content" 
        :system-package-name                    "content_input"
        :select-package-group                   "perform_action_package_groups"
        :select-system-package                  "perform_action_packages"
        :pkg-install-status                     "//td[@class='package_action_status']/a[@class='subpanel_element']"

        ;;system-edit details
        :system-name-text-edit                  "system[name]"
        :system-description-text-edit           "system[description]"
        :system-location-text-edit              "system[location]"
        :system-service-level-select            "system[serviceLevel]"
        :system-release-version-select          "system[releaseVer]"
        :system-environment                     "//div[@id='environment_path_selector']"
        :system-operating-system                "//label[contains(.,'OS')]/../following-sibling::*[1]"
        :system-save-environment                "//input[@value='Save']"

        ;;systemgroups pane
        :new-system-groups                      "//a[@id='new']"
        :create-system-groups                   "group_save"
        :system-group-name-text                 "system_group[name]"
        :system-group-description-text          "system_group[description]"
        :systems-sg                             "//div[@class='panel-content']//a[.='Systems']"
        :system-groups-hostname-toadd           "add_system_input"
        :system-groups-add-system               "add_system"
        :system-groups-remove-system            "remove_systems"
        :system-group-copy                      (locators/link "Copy")
        :system-group-copy-name-text            "name_input"
        :system-group-copy-description-text     "description_input"
        :system-group-copy-submit               "copy_button"
        :system-group-remove                    (locators/link "Remove")
        :system-group-total                     "//fieldset[contains(.,'Total')]/div[2]/a"
        :system-group-confirm-only-system-group "//span[.='No, only delete the system group.']"
        :system-group-unlimited                 "//input[@class='unlimited_members']"
        :save-new-limit                          "//button[.='Save']"
        :system-group-limit-value               "system_group[max_systems]"
        
        ;;subscriptions pane
        :subscribe                              "sub_submit"
        :unsubscribe                            "unsub_submit"

        ;;Activationkeys subtab
        :new-activation-key                     "new"
        :activation-key-name-text               "activation_key[name]"
        :activation-key-description-text        "activation_key[description]"
        :activation-key-template-select         "activation_key[system_template_id]"
        :save-activation-key                    "save_key"
        :applied-subscriptions                  "//a[.='Applied Subscriptions']"
        :available-subscriptions                "//a[.='Available Subscriptions']"
        :add-subscriptions-to-activation-key    "//input[@id='subscription_submit_button']"            
        :remove-activation-key                  (locators/link "Remove Activation Key")
        :subscriptions-right-nav                "//div[contains(@class, 'panel-content')]//a[.='Subscriptions']"
        :release-version-text                   "system[releaseVer]"})

(sel/template-fns
 {fetch-applied-subscriptions     "xpath=(//table[@class='filter_table']//a[contains(@href, 'providers') or contains(@href, 'subscriptions')])[%s]"
  subscription-available-checkbox "//div[@id='panel-frame']//table[@id='subscribeTable']//td[contains(normalize-space(.),'%s')]//input[@type='checkbox']"
  subscription-current-checkbox   "//div[@id='panel-frame']//table[@id='unsubscribeTable']//td[contains(normalize-space(.),'%s')]//input[@type='checkbox']"
  system-checkbox                 "//input[@class='system_checkbox' and @type='checkbox' and parent::td[normalize-space(.)='%s']]"
  system-environment-checkbox     "//input[@class='node_select' and @type='checkbox' and @data-node_name='%s']"})

;; Nav

(swap! locators/page-tree ui.navigate/graft :top-level
       (ui.navigate/nav-tree [:systems-tab [] (sel/browser mouseOver :systems)
                              [:systems-all-page [] (sel/browser clickAndWait :all)
                               [:new-system-page [] (sel/browser click :new-system)]
                               [:system-subscriptions-page [system-name] (nav/choose-left-pane system-name)
                                [:named-systems-page [] (sel/browser click :details)]
                                [:named-system-page-content [] (sel/browser click :system-content-select)]]]
                              [:system-groups-page [] (sel/browser clickAndWait :system-groups)
                               [:new-system-groups-page [] (sel/browser click :new-system-groups)]
                               [:named-system-group-page [system-group-name] (nav/choose-left-pane system-group-name)
                                [:system-group-systems-page [] (sel/browser click :systems-sg)]
                                [:system-group-details-page [] (sel/browser click :details)]]]
                              [:systems-by-environment-page [] (sel/browser clickAndWait :by-environments)
                               [:systems-environment-page [env-name] (locators/select-environment-widget env-name)
                                [:named-system-environment-page [system-name]
                                 (nav/choose-left-pane locators/left-pane-item system-name)]]]]))

;; Tasks

(defn create
  "Creates a system"
  [name & [{:keys [sockets system-arch]}]]
  (ui/navigate :new-system-page)
  (sel/fill-ajax-form {:system-name-text name
                       :system-sockets-text sockets
                       :system-arch-select (or system-arch "x86_64")}
                      :create-system)
  (notification/check-for-success {:match-pred (notification/request-type? :sys-create)}))

(defn edit
  "Edits the properties of the given system. Optionally specify a new
  name, a new description, and a new location."
  [name {:keys [new-name description location release-version]}]
  (ui/navigate :named-systems-page {:system-name name})
  (ui/in-place-edit {:system-name-text-edit new-name
                     :system-description-text-edit description
                     :system-location-text-edit location
                     :system-release-version-select release-version}))

(defn edit-system-environment [system-name new-environment]
  (assert (not (blank? new-environment))) 
  (ui/navigate :named-systems-page {:system-name system-name})
  (sel/browser click :system-environment)
  (sel/browser check (system-environment-checkbox new-environment))
  (sel/browser click :system-save-environment))

(defn subscribe
  "Subscribes the given system to the products. (products should be a
  list). Can also set the auto-subscribe for a particular SLA.
  auto-subscribe must be either true or false to select a new setting
  for auto-subscribe and SLA. If auto-subscribe is nil, no changes
  will be made."
  [{:keys [system-name add-products remove-products auto-subscribe sla]}]
  (ui/navigate :system-subscriptions-page {:system-name system-name})
  (when-not (nil? auto-subscribe)
    (ui/in-place-edit {:system-service-level-select (format "Auto-subscribe %s, %s"
                                                            (if auto-subscribe "On" "Off")
                                                            sla)}))
  (let [sub-unsub-fn (fn [content checkbox-fn submit]
                       (when-not (empty? content)
                         (doseq [item content]
                           (sel/browser check (checkbox-fn item)))
                         (sel/browser click submit)) )]
    (sub-unsub-fn add-products subscription-available-checkbox :subscribe)
    (sub-unsub-fn remove-products subscription-current-checkbox :unsubscribe))
  (notification/check-for-success {:match-pred (notification/request-type? (if (or add-products remove-products)
                                                                             :sys-update-subscriptions
                                                                             :sys-update))}))

(defn create-group
  "Creates a system-group"
  [name & [{:keys [description]}]]
  (ui/navigate :new-system-groups-page)
  (sel/fill-ajax-form
   {:system-group-name-text name
    :system-group-description-text description}
   :create-system-groups)
  (notification/check-for-success {:match-pred (notification/request-type? :sysgrps-create)}))

(defn add-to-group
  "Adds a system to a System-Group"
  [system-group system-name]
  (ui/navigate :named-system-group-page {:system-group-name system-group})
  (comment (sel/browser setText :system-groups-hostname-toadd system-name)
           (sel/browser typeKeys :system-groups-hostname-toadd " ")
           (Thread/sleep 5000)
           (sel/browser click :system-groups-add-system)
           (check-for-success))
  (sel/fill-ajax-form [:system-groups-hostname-toadd system-name
                       ;;try to trigger autocomplete via javascript -
                       ;;hackalert - see
                       ;;https://bugzilla.redhat.com/show_bug.cgi?id=865472 -jweiss
                       #(sel/browser getEval %) ["window.$(\"#add_system_input\").autocomplete('search')"]
                       #(Thread/sleep 5000) []]
                      :system-groups-add-system)
  (notification/check-for-success {:match-pred (notification/request-type? :sysgrps-add-sys)}))

(defn remove-from-group
  "Remove a system from a System-Group"
  [system-group system-name]
  (ui/navigate :named-system-group-page {:system-group-name system-group})
  (sel/browser click (system-checkbox system-name))
  (sel/browser click :system-groups-remove-system))

(defn copy-group
  "Clones a system group, given the name of the original system group
   to clone, and the new name and description."
  [orig-name new-name & [{:keys [description]}]]
  (ui/navigate :named-system-group-page {:system-group-name orig-name})
  (sel/browser click :system-group-copy)
  (sel/fill-ajax-form {:system-group-copy-name-text new-name
                       :system-group-copy-description-text description}
                      :system-group-copy-submit)
  (notification/check-for-success {:match-pred (notification/request-type? :sysgrps-copy)}))

(defn remove-group [system-group & [{:keys [also-remove-systems?]}]]
  (ui/navigate :named-system-group-page {:system-group-name system-group})
  (sel/browser click :system-group-remove)
  (sel/browser click :confirmation-yes)
  (sel/browser click (if also-remove-systems?
                       :confirmation-yes
                       :system-group-confirm-only-system-group))
  (notification/check-for-success
   {:match-pred  (notification/request-type? (if also-remove-systems?
                                               :sysgrps-destroy-sys
                                               :sysgrps-destroy))}))

(defn edit-group "Change the value of limit field in system group"
  [sg-name {:keys [new-limit new-sg-name description]}]
  (ui/navigate :system-group-details-page {:system-group-name sg-name})
  (let [needed-flipping (and new-limit
                             (not= (= new-limit :unlimited)
                                   (sel/browser isChecked :system-group-unlimited)))]
    (if (and new-limit (not= new-limit :unlimited))
      (do (sel/browser uncheck :system-group-unlimited)
          (sel/fill-ajax-form {:system-group-limit-value (str new-limit)}
                              :save-new-limit ))
      (sel/browser check :system-group-unlimited))
    (when needed-flipping (notification/check-for-success
                           {:match-pred (notification/request-type? :sysgrps-update)})))
  (ui/in-place-edit {:system-group-name-text new-sg-name
                     :system-group-description-text description}))

(defn get-group-system-count "Get number of systems in system group"
  [sg-name]
  (ui/navigate :system-group-details-page {:system-group-name sg-name})
  (Integer/parseInt (sel/browser getText :system-group-total)))

(defn get-system-env "Get current environment of the system"
  [system-name]
  (ui/navigate :named-systems-page {:system-name system-name})
  (sel/browser getText :system-environment))

(defn get-os "Get operating system of the system"
  [name]
  (ui/navigate :named-systems-page {:system-name name})
  (sel/browser getText :system-operating-system))

(defn get-subscriptions-in-activation-key "Get applied susbscription info from activation key"
  [name]
  (ui/navigate :named-activation-key-page {:activation-key-name name})
  (sel/browser click :applied-subscriptions)
  (ui/extract-list fetch-applied-subscriptions))

(defn add-package [name {:keys [package package-group]}]
  (ui/navigate :named-system-page-content {:system-name name})
  (sel/browser click :system-content-packages)
  (doseq [[items exp-status is-group?] [[package "Add Package Complete" false]
                                        [package-group "Add Package Group Complete" true]]]
    (when items
      (when is-group? (sel/browser click :select-package-group))
      (sel/->browser (setText :system-package-name items)
                     (typeKeys :system-package-name items)
                     (click :system-add-content))
      (Thread/sleep 50000)
      (assert/is (= exp-status
                    (sel/browser getText :pkg-install-status))))))

(defn remove-package [name {:keys [package package-group]}]
  (ui/navigate :named-system-page-content {:system-name name})
  (sel/browser click :system-content-packages)
  (doseq [[items exp-status is-group?] [[package "Remove Package Complete" false]
                                        [package-group "Remove Package Group Complete" true]]]
    (when items
      (when is-group? (sel/browser click :select-package-group))
      (sel/->browser (setText :system-package-name items)
                     (typeKeys :system-package-name items)
                     (click :system-remove-content))
      (Thread/sleep 50000)
      (assert/is (= exp-status
                    (sel/browser getText :pkg-install-status))))))
