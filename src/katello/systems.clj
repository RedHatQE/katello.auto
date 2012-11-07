(ns katello.systems
  (:require [com.redhat.qe.auto.selenium.selenium :refer [browser ->browser]]
            [clojure.string :refer [blank?]]
            (katello [locators :as locators] 
                     [notifications :as notification] 
                     [ui-tasks :refer :all])))

(defn create
  "Creates a system"
   [name & [{:keys [sockets system-arch]}]]
   (navigate :new-system-page)
   (fill-ajax-form {:system-name-text name
                    :system-sockets-text sockets
                    :system-arch-select (or system-arch "x86_64")}
                    :create-system)
   (notification/check-for-success {:match-pred (notification/request-type? :sys-create)}))

(defn edit
  "Edits the properties of the given system. Optionally specify a new
  name, a new description, and a new location."
  [name {:keys [new-name description location release-version]}]
  (navigate :named-systems-page {:system-name name})
  (in-place-edit {:system-name-text-edit new-name
                  :system-description-text-edit description
                  :system-location-text-edit location
                  :system-release-version-select release-version}))

(defn edit-system-environment [system-name new-environment]
  (assert (not (blank? new-environment))) 
  (navigate :named-systems-page {:system-name system-name})
  (browser click :system-environment)
  (browser check (locators/system-environment-checkbox new-environment))
  (browser click :system-save-environment))

(defn subscribe
  "Subscribes the given system to the products. (products should be a
  list). Can also set the auto-subscribe for a particular SLA.
  auto-subscribe must be either true or false to select a new setting
  for auto-subscribe and SLA. If auto-subscribe is nil, no changes
  will be made."
  [{:keys [system-name add-products remove-products auto-subscribe sla]}]
  (navigate :system-subscriptions-page {:system-name system-name})
  (when-not (nil? auto-subscribe)
    (in-place-edit {:system-service-level-select (format "Auto-subscribe %s, %s"
                                                         (if auto-subscribe "On" "Off")
                                                         sla)}))
  (let [sub-unsub-fn (fn [content checkbox-fn submit]
                       (when-not (empty? content)
                         (doseq [item content]
                           (browser check (checkbox-fn item)))
                         (browser click submit)) )]
    (sub-unsub-fn add-products locators/subscription-available-checkbox :subscribe)
    (sub-unsub-fn remove-products locators/subscription-current-checkbox :unsubscribe))
  (notification/check-for-success {:match-pred (notification/request-type? (if (or add-products remove-products)
                                                                             :sys-update-subscriptions
                                                                             :sys-update))}))

(defn create-group
  "Creates a system-group"
   [name & [{:keys [description]}]]
   (navigate :new-system-groups-page)
   (fill-ajax-form {:system-group-name-text name
                    :system-group-description-text description}
                    :create-system-groups)
   (notification/check-for-success {:match-pred (notification/request-type? :sysgrps-create)}))

(defn add-to-group
  "Adds a system to a System-Group"
  [system-group system-name]
  (navigate :named-system-group-page {:system-group-name system-group})
  (comment (browser setText :system-groups-hostname-toadd system-name)
           (browser typeKeys :system-groups-hostname-toadd " ")
           (Thread/sleep 5000)
           (browser click :system-groups-add-system)
           (check-for-success))
  (fill-ajax-form [:system-groups-hostname-toadd system-name
                   ;;try to trigger autocomplete via javascript -
                   ;;hackalert - see
                   ;;https://bugzilla.redhat.com/show_bug.cgi?id=865472 -jweiss
                   #(browser getEval %) ["window.$(\"#add_system_input\").autocomplete('search')"]
                   #(Thread/sleep 5000) []]
                  :system-groups-add-system)
  (notification/check-for-success {:match-pred (notification/request-type? :sysgrps-add-sys)}))

(defn remove-from-group
  "Remove a system from a System-Group"
   [system-group system-name]
   (navigate :named-system-group-page {:system-group-name system-group})
   (browser click (locators/system-checkbox system-name))
   (browser click :system-groups-remove-system))

(defn copy-group
  "Clones a system group, given the name of the original system group
   to clone, and the new name and description."
  [orig-name new-name & [{:keys [description]}]]
  (navigate :named-system-group-page {:system-group-name orig-name})
  (browser click :system-group-copy)
  (fill-ajax-form {:system-group-copy-name-text new-name
                   :system-group-copy-description-text description}
                  :system-group-copy-submit)
  (notification/check-for-success {:match-pred (notification/request-type? :sysgrps-copy)}))

(defn remove-group [system-group & [{:keys [also-remove-systems?]}]]
  (navigate :named-system-group-page {:system-group-name system-group})
  (browser click :system-group-remove)
  (browser click :confirmation-yes)
  (browser click (if also-remove-systems?
                   :confirmation-yes
                   :system-group-confirm-only-system-group))
  (notification/check-for-success
   {:match-pred  (notification/request-type? (if also-remove-systems?
                                               :sysgrps-destroy-sys
                                               :sysgrps-destroy))}))

(defn edit-group "Change the value of limit field in system group"
  [sg-name {:keys [new-limit new-sg-name description]}]
  (navigate :system-group-details-page {:system-group-name sg-name})
  (let [needed-flipping (and new-limit
                            (not= (= new-limit :unlimited)
                                  (browser isChecked :system-group-unlimited)))]
    (if (and new-limit (not= new-limit :unlimited))
      (do (browser uncheck :system-group-unlimited)
          (fill-ajax-form {:system-group-limit-value (str new-limit)}
                          :save-new-limit ))
      (browser check :system-group-unlimited))
    (when needed-flipping (notification/check-for-success
                           {:match-pred (notification/request-type? :sysgrps-update)})))
  (in-place-edit {:system-group-name-text new-sg-name
                  :system-group-description-text description}))

(defn get-group-system-count "Get number of systems in system group"
  [sg-name]
  (navigate :system-group-details-page {:system-group-name sg-name})
  (Integer/parseInt (browser getText :system-group-total)))

(defn get-system-env "Get current environment of the system"
  [system-name]
  (navigate :named-systems-page {:system-name system-name})
  (browser getText :system-environment))

(defn get-os "Get operating system of the system"
  [name]
  (navigate :named-systems-page {:system-name name})
  (browser getText :system-operating-system))

(defn get-subscriptions-in-activation-key "Get applied susbscription info from activation key"
  [name]
  (navigate :named-activation-key-page {:activation-key-name name})
  (browser click :applied-subscriptions)
  (extract-list locators/fetch-applied-subscriptions))

