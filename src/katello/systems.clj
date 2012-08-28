(ns katello.systems
  (:require [com.redhat.qe.auto.selenium.selenium :refer [browser]]
            (katello [locators :as locators] 
                     [notifications :refer [check-for-success]] 
                     [ui-tasks :refer :all])))

(defn create-system
  "Creates a system"
   [name & [{:keys [sockets system-arch]}]]
   (navigate :new-system-page)
   (fill-ajax-form {:system-name-text name
                    :system-sockets-text sockets
                    :system-arch-select (or system-arch "x86_64")}
                    :create-system)
   (check-for-success))

(defn edit-system
  "Edits the properties of the given system. Optionally specify a new
  name, a new description, and a new location."
  [name {:keys [new-name description location release-version]}]
  (navigate :named-systems-page {:system-name name})
  (in-place-edit {:system-name-text-edit new-name
                  :system-description-text-edit description
                  :system-location-text-edit location
                  :system-release-version-select release-version}))

(defn subscribe-system
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
  (check-for-success))

(defn create-system-group
  "Creates a system-group"
   [name & [{:keys [description]}]]
   (navigate :new-system-groups-page)
   (fill-ajax-form {:system-group-name-text name
                    :system-group-description-text description}
                    :create-system-groups)
   (check-for-success))

(defn add-to-system-group
  "Adds a system to a System-Group"
   [system-group system-name]
   (navigate :named-system-group-page {:system-group-name system-group})
   (fill-ajax-form {:system-groups-hostname-toadd system-name}
                    :system-groups-add-system))

(defn copy-system-group
  "Clones a system group, given the name of the original system group
   to clone, and the new name and description."
  [orig-name new-name & [{:keys [description]}]]
  (navigate :named-system-group-page {:system-group-name orig-name})
  (browser click :system-group-copy)
  (fill-ajax-form {:system-group-copy-name-text new-name
                   :system-group-copy-description-text description}
                  :system-group-copy-submit)
  (check-for-success))

(defn remove-system-group [system-group & [{:keys [also-remove-systems?]}]]
  (navigate :named-system-group-page {:system-group-name system-group})
  (browser click :system-group-remove)
  (browser click :confirmation-yes)
  (browser click (if also-remove-systems?
                   :confirmation-yes
                   :system-group-confirm-only-system-group))
  (check-for-success))

(defn edit-system-group "Change the value of limit field in system group"
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
    (when needed-flipping (check-for-success)))
  (in-place-edit {:system-group-name-text new-sg-name
                  :system-group-description-text description}))


