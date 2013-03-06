(ns katello.systems
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            [clojure.string :refer [blank?]]
            [slingshot.slingshot :refer [throw+]]
            [katello.tasks :refer [expecting-error]]
            [test.assert :as assert]
            (katello [navigation :as nav]
                     [notifications :as notification]
                     [ui :as ui]
                     [ui-common :as common])))

;; Locators

(sel/template-fns
 {subscription-available-checkbox "//div[@id='panel-frame']//table[@id='subscribeTable']//td[contains(normalize-space(.),'%s')]//input[@type='checkbox']"
  subscription-current-checkbox   "//div[@id='panel-frame']//table[@id='unsubscribeTable']//td[contains(normalize-space(.),'%s')]//input[@type='checkbox']"
  checkbox                        "//input[@class='system_checkbox' and @type='checkbox' and parent::td[normalize-space(.)='%s']]"
  sysgroup-checkbox               "//input[@title='%s']"
  activation-key-link             (ui/link "%s")
  environment-checkbox            "//input[@class='node_select' and @type='checkbox' and @data-node_name='%s']"
  system-detail-textbox           "//label[contains(.,'%s')]/../following-sibling::*[1]"})

(ui/deflocators
  {::new                         "new"
   ::create                      "system_submit"
   ::name-text                   "system[name]"
   ::sockets-text                "system[sockets]"
   ::arch-select                 "arch[arch_id]"
   ::remove                      (ui/link "Remove System")
   ::multi-remove                (ui/link "Remove System(s)")
   ::sys-tab                     (ui/link "Systems")
   ::confirm-yes                 "//input[@value='Yes']"
   ::select-sysgrp               "//button[@type='button']"
   ::add-sysgrp                  "//input[@value='Add']"
   ::confirm-to-yes              "xpath=(//input[@value='Yes'])[4]"
   ::total-sys-count             "total_items_count"
   
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
   ::save-button                 "//button[@type='submit']"
   ::cancel-button               "//button[@type='cancel']"
   
   ;;subscriptions pane
   ::subscriptions               (ui/menu-link "systems_subscriptions")
   ::subscribe                   "sub_submit"
   ::unsubscribe                 "unsub_submit"})

;; Nav

(nav/defpages (common/pages)
  [::page
   [::new-page [] (browser click ::new)]
   [::named-page [system-name] (nav/choose-left-pane system-name)
    [::details-page [] (browser click ::details)]
    [::subscriptions-page [] (browser click ::subscriptions)]
    [::content-menu [] (browser mouseOver ::content-link)
     [::content-software-page [] (browser click ::software-link)]
     [::content-packages-page [] (browser click ::packages-link)]
     [::content-errata-page [] (browser click ::errata-link)]]]]
  [::by-environments-page
   [::environment-page [env-name] (nav/select-environment-widget env-name)
    [::named-by-environment-page [system-name] (nav/choose-left-pane system-name)]]])

;; Tasks

(defn create
  "Creates a system"
  [name & [{:keys [sockets system-arch]}]]
  (nav/go-to ::new-page)
  (sel/fill-ajax-form {::name-text name
                       ::sockets-text sockets
                       ::arch-select (or system-arch "x86_64")}
                      ::create)
  (notification/check-for-success {:match-pred (notification/request-type? :sys-create)}))

(defn delete "Deletes the selected system."
  [system-name]
  (nav/go-to ::named-page {:system-name system-name})
  (browser click ::remove)
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success {:match-pred (notification/request-type? :sys-destroy)}))

(defn select-multisys-with-ctrl
  [system-names]
  (browser clickAndWait ::sys-tab)
  (browser controlKeyDown)
  (doseq [system-name system-names]
    (nav/scroll-to-left-pane-item system-name)
    (nav/choose-left-pane system-name))
  (browser controlKeyUp))

(defn multi-delete "Delete multiple systems."
  [system-names]
  (select-multisys-with-ctrl system-names)
  (browser click ::multi-remove)
  (browser click ::confirm-yes)
  (notification/check-for-success {:match-pred (notification/request-type? :sys-bulk-destroy)}))

(defn add-bulk-sys-to-sysgrp 
  "Adding systems to system group in bulk by pressing ctrl, from right-pane of system tab."
  [system-names group-name]
  (select-multisys-with-ctrl system-names)
  (browser click ::select-sysgrp)
  (browser click (sysgroup-checkbox group-name))
  (browser click ::add-sysgrp)
  (browser click ::confirm-to-yes)
  (notification/check-for-success))

(defn edit
  "Edits the properties of the given system. Optionally specify a new
  name, a new description, and a new location."
  [name {:keys [new-name description location release-version]}]
  (nav/go-to ::details-page {:system-name name})
  (common/in-place-edit {::name-text-edit new-name
                         ::description-text-edit description
                         ::location-text-edit location
                         ::release-version-select release-version}))

(defn edit-sysname
  "Edits system-name"
  [name new-name save?]
  (nav/go-to ::details-page {:system-name name})
  (let [old-name (browser getText ::edit-sysname)]
    (browser click ::edit-sysname)
    (browser setText ::name-text-edit new-name)
    (if save?
      (do
        (browser click ::save-button)
        (notification/check-for-success)
        (when-not (= new-name (browser getText ::edit-sysname))
          (throw+ {:type ::sysname-not-edited
                   :msg "Still getting old system name."})))
      (do
        (browser click ::cancel-button)
        (when-not (= name old-name)
          (throw+ {:type ::sysname-edited-anyway
                   :msg "System name changed even after clicking cancel button."}))))))

(defn set-environment "Move a system to a new environment."
  [system-name new-environment]
  (assert (not (blank? new-environment))) 
  (nav/go-to ::details-page {:system-name system-name})
  (sel/->browser (click ::environment)
                 (check (environment-checkbox new-environment))
                 (click ::save-environment)))

(defn validate-activation-key-link
  "Validate activation key link under system details"
  [name keyname]
  (nav/go-to ::details-page {:system-name name})
  (when (browser isElementPresent (activation-key-link keyname))
    (browser clickAndWait (activation-key-link keyname))))

(defn subscribe
  "Subscribes the given system to the products. (products should be a
  list). Can also set the auto-subscribe for a particular SLA.
  auto-subscribe must be either true or false to select a new setting
  for auto-subscribe and SLA. If auto-subscribe is nil, no changes
  will be made."
  [{:keys [system-name add-products remove-products auto-subscribe sla]}]
  (nav/go-to ::subscriptions-page {:system-name system-name})
  (when-not (nil? auto-subscribe)
    (common/in-place-edit {::service-level-select (format "Auto-subscribe %s, %s"
                                                          (if auto-subscribe "On" "Off")
                                                          sla)}))
  (let [sub-unsub-fn (fn [content checkbox-fn submit]
                       (when-not (empty? content)
                         (doseq [item content]
                           (browser check (checkbox-fn item)))
                         (browser click submit)) )]
    (sub-unsub-fn add-products subscription-available-checkbox ::subscribe)
    (sub-unsub-fn remove-products subscription-current-checkbox ::unsubscribe))
  (notification/check-for-success {:match-pred (notification/request-type? (if (or add-products remove-products)
                                                                             :sys-update-subscriptions
                                                                             :sys-update))}))

(defn environment "Get current environment of the system"
  [system-name]
  (nav/go-to ::details-page {:system-name system-name})
  (browser getText ::environment))

(defn get-details [system-name]
  (nav/go-to ::details-page {:system-name system-name})
  (let [details ["Name" "Description" "OS" "Release" "Release Version"
                 "Arch" "RAM (MB)" "Sockets" "Location" "Environment"
                 "Checked In" "Registered" "Last Booted" "Activation Key"
                 "System Type" "Host"]]
    (zipmap details
            (doall (for [detail details]
                     (browser getText (system-detail-textbox detail)))))))

(defn add-package "Add a package or package group to a system."
  [system-name {:keys [package package-group]}]
  (nav/go-to ::content-packages-page {:system-name system-name})
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
  [system-name {:keys [package package-group]}]
  (nav/go-to ::content-packages-page {:system-name system-name})
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
