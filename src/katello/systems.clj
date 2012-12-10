(ns katello.systems
  (:require [com.redhat.qe.auto.selenium.selenium :as sel]
            [clojure.string :refer [blank?]]
            [test.assert :as assert]
            (katello [navigation :as nav]
                     [notifications :as notification]
                     [ui-common :as ui]
                     [menu :as menu])))

;; Locators

(swap! ui/locators merge
       {::new                         "new"
        ::create                      "system_submit"
        ::name-text                   "system[name]"
        ::sockets-text                "system[sockets]"
        ::arch-select                 "arch[arch_id]"
        ::content-select              "xpath=(//li[@id='content']/a)[2]"
        ::content-packages            (ui/link "Packages")
        ::add-content		      "add_content"
        ::remove-content              "remove_content" 
        ::package-name                "content_input"
        ::select-package-group        "perform_action_package_groups"
        ::select-package              "perform_action_packages"
        ::pkg-install-status           "//td[@class='package_action_status']/a[@class='subpanel_element']"

        ;;system-edit details
        ::name-text-edit              "system[name]"
        ::description-text-edit       "system[description]"
        ::location-text-edit          "system[location]"
        ::service-level-select        "system[serviceLevel]"
        ::release-version-select      "system[releaseVer]"
        ::environment                 "//div[@id='environment_path_selector']"
        ::operating-system            "//label[contains(.,'OS')]/../following-sibling::*[1]"
        ::save-environment            "//input[@value='Save']"

        ;;subscriptions pane
        ::subscribe                   "sub_submit"
        ::unsubscribe                 "unsub_submit"})

(sel/template-fns
 {subscription-available-checkbox "//div[@id='panel-frame']//table[@id='subscribeTable']//td[contains(normalize-space(.),'%s')]//input[@type='checkbox']"
  subscription-current-checkbox   "//div[@id='panel-frame']//table[@id='unsubscribeTable']//td[contains(normalize-space(.),'%s')]//input[@type='checkbox']"
  checkbox                 "//input[@class='system_checkbox' and @type='checkbox' and parent::td[normalize-space(.)='%s']]"
  environment-checkbox     "//input[@class='node_select' and @type='checkbox' and @data-node_name='%s']"})

;; Nav

(nav/add-subnavigation
 ::page
 [::new-page [] (sel/browser click ::new)]
 [::subscriptions-page [system-name] (nav/choose-left-pane system-name)
  [::named-page [] (sel/browser click :details)]
  [::named-page-content [] (sel/browser click ::content-select)]]
 [::by-environment-page [] (sel/browser clickAndWait :by-environments)
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

(defn edit
  "Edits the properties of the given system. Optionally specify a new
  name, a new description, and a new location."
  [name {:keys [new-name description location release-version]}]
  (nav/go-to ::named-page {:system-name name})
  (ui/in-place-edit {::name-text-edit new-name
                     ::description-text-edit description
                     ::location-text-edit location
                     ::release-version-select release-version}))

(defn edit-system-environment [system-name new-environment]
  (assert (not (blank? new-environment))) 
  (nav/go-to ::named-page {:system-name system-name})
  (sel/browser click ::environment)
  (sel/browser check (environment-checkbox new-environment))
  (sel/browser click ::save-environment))

(defn subscribe
  "Subscribes the given system to the products. (products should be a
  list). Can also set the auto-subscribe for a particular SLA.
  auto-subscribe must be either true or false to select a new setting
  for auto-subscribe and SLA. If auto-subscribe is nil, no changes
  will be made."
  [{:keys [system-name add-products remove-products auto-subscribe sla]}]
  (nav/go-to ::subscriptions-page {:system-name system-name})
  (when-not (nil? auto-subscribe)
    (ui/in-place-edit {::service-level-select (format "Auto-subscribe %s, %s"
                                                            (if auto-subscribe "On" "Off")
                                                            sla)}))
  (let [sub-unsub-fn (fn [content checkbox-fn submit]
                       (when-not (empty? content)
                         (doseq [item content]
                           (sel/browser check (checkbox-fn item)))
                         (sel/browser click submit)) )]
    (sub-unsub-fn add-products subscription-available-checkbox ::subscribe)
    (sub-unsub-fn remove-products subscription-current-checkbox ::unsubscribe))
  (notification/check-for-success {:match-pred (notification/request-type? (if (or add-products remove-products)
                                                                             :sys-update-subscriptions
                                                                             :sys-update))}))

(defn get-system-env "Get current environment of the system"
  [system-name]
  (nav/go-to ::named-page {:system-name system-name})
  (sel/browser getText ::environment))

(defn get-os "Get operating system of the system"
  [system-name]
  (nav/go-to ::named-page {:system-name system-name})
  (sel/browser getText ::operating-system))

(defn add-package [system-name {:keys [package package-group]}]
  (nav/go-to ::named-page-content {:system-name system-name})
  (sel/browser click ::content-packages)
  (doseq [[items exp-status is-group?] [[package "Add Package Complete" false]
                                        [package-group "Add Package Group Complete" true]]]
    (when items
      (when is-group? (sel/browser click ::select-package-group))
      (sel/->browser (setText ::package-name items)
                     (typeKeys ::package-name items)
                     (click ::add-content))
      (Thread/sleep 50000)
      (assert/is (= exp-status
                    (sel/browser getText ::pkg-install-status))))))

(defn remove-package [system-name {:keys [package package-group]}]
  (nav/go-to ::named-page-content {:system-name system-name})
  (sel/browser click ::content-packages)
  (doseq [[items exp-status is-group?] [[package "Remove Package Complete" false]
                                        [package-group "Remove Package Group Complete" true]]]
    (when items
      (when is-group? (sel/browser click ::select-package-group))
      (sel/->browser (setText ::package-name items)
                     (typeKeys ::package-name items)
                     (click ::remove-content))
      (Thread/sleep 50000)
      (assert/is (= exp-status
                    (sel/browser getText ::pkg-install-status))))))
