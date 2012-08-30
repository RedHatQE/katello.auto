(ns katello.sync-management
  (:require [com.redhat.qe.auto.selenium.selenium 
              :refer [browser loop-with-timeout]]
            (katello [locators :as locators] 
                     [notifications :refer [check-for-success]] 
                     [ui-tasks :refer [navigate fill-ajax-form in-place-edit]]))
  (:import [com.thoughtworks.selenium SeleniumException]
           [java.text SimpleDateFormat]))

(def plan-dateformat (SimpleDateFormat. "MM/dd/yyyy"))
(def plan-timeformat (SimpleDateFormat. "hh:mm aa"))
(defn- date-str [d] (.format plan-dateformat d))
(defn- time-str [d] (.format plan-timeformat d))

(defn- split-date [{:keys [start-date start-date-literal start-time-literal]}]
  (list (if start-date (date-str start-date) start-date-literal)
        (if start-date (time-str start-date) start-time-literal)))

(defn create-plan
  "Creates a sync plan with the given properties. Either specify a
  start-date (as a java.util.Date object) or a separate string for
  start-date-literal 'MM/dd/yyyy', and start-time-literal 'hh:mm aa'
  The latter can also be used to specify invalid dates for validation
  tests."
  [{:keys [name description interval start-date
           start-date-literal start-time-literal] :as m}]
  (navigate :new-sync-plan-page)
  (let [[date time] (split-date m)]
    (fill-ajax-form {:sync-plan-name-text name
                     :sync-plan-description-text description
                     :sync-plan-interval-select interval
                     :sync-plan-time-text time
                     :sync-plan-date-text date}
                    :save-sync-plan)
    (check-for-success)))

(defn edit-plan
  "Edits the given sync plan with optional new properties. See also
  create-sync-plan for more details."
  [name {:keys [new-name
                description interval start-date start-date-literal
                start-time-literal] :as m}]
  (navigate :named-sync-plan-page {:sync-plan-name name})
  (let [[date time] (split-date m)]
    (in-place-edit {:sync-plan-name-text new-name
                    :sync-plan-description-text description
                    :sync-plan-interval-select interval
                    :sync-plan-time-text time
                    :sync-plan-date-text date})))

(defn schedule
  "Schedules the given list of products to be synced using the given
  sync plan name."
  [{:keys [products plan-name]}]
  (navigate :sync-schedule-page)
  (doseq [product products]
    (browser click (locators/schedule product)))
  (browser click (locators/sync-plan plan-name))
  (browser clickAndWait :apply-sync-schedule )
  (check-for-success))

(defn current-plan
  "Returns a map of what sync plan a product is currently scheduled
  for. nil if UI says 'None'"
  [product-names]
  (navigate :sync-schedule-page)
  (zipmap product-names
          (replace {"None" nil}
                   (doall (for [product-name product-names]
                            (browser getText (locators/product-schedule product-name)))))))

(def messages {:ok "Sync complete."
               :fail "Error syncing!"})

(defn complete-status
  "Returns final status if complete. If sync is still in progress, not
  synced, or queued, returns nil."
  [product]
  (some #{(browser getText (locators/provider-sync-progress product))}
        (vals messages)))

(defn success? "Returns true if given sync result is a success."
  [res]
  (= res (:ok messages)))


(defn perform-sync
  "Syncs the given list of repositories. Also takes an optional
  timeout (in ms) of how long to wait for the sync to complete before
  throwing an error.  Default timeout is 2 minutes."
  [repos & [{:keys [timeout]}]]
  (navigate :sync-status-page)
  (doseq [repo repos]
    (browser check (locators/provider-sync-checkbox repo)))
  (browser click :synchronize-now)
  (browser sleep 10000)
  (zipmap repos (for [repo repos]
                     (loop-with-timeout (or timeout 120000) []
                       (or (complete-status repo)
                           (do (Thread/sleep 10000)
                               (recur)))))))
