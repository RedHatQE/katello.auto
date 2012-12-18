(ns katello.sync-management
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            (katello [navigation :as nav] 
                     [notifications :as notification] 
                     [ui-common :as common]
                     [ui :as ui]))
  (:import [java.text SimpleDateFormat]))

;; Locators

(ui/deflocators
  {::apply-schedule        "apply_button"
   ::new-plan              "new"
   ::plan-name-text        "sync_plan[name]"
   ::plan-description-text "sync_plan[description]"
   ::plan-interval-select  "sync_plan[interval]"
   ::plan-date-text        "sync_plan[plan_date]"
   ::plan-time-text        "sync_plan[plan_time]"
   ::save-plan             "plan_save"
   ::synchronize-now       "sync_button"})

(sel/template-fns
 {product-schedule  "//div[normalize-space(.)='%s']/following-sibling::div[1]"
  provider-checkbox "//table[@id='products_table']//label[normalize-space(.)='%s']/..//input"
  provider-progress "//tr[td/label[normalize-space(.)='%s']]/td[5]" 
  plan              "//div[@id='plans']//div[normalize-space(.)='%s'"
  schedule          "//div[normalize-space(.)='%s']"})

;; Nav

(nav/defpages (common/pages)
  [::plans-page
   [::named-plan-page [sync-plan-name]
    (nav/choose-left-pane sync-plan-name)]
   [::new-plan-page [] (browser click ::new-plan)]])

;; Tasks

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
  (nav/go-to ::new-plan-page)
  (let [[date time] (split-date m)]
    (sel/fill-ajax-form {::plan-name-text name
                         ::plan-description-text description
                         ::plan-interval-select interval
                         ::plan-time-text time
                         ::plan-date-text date}
                        ::save-plan)
    (notification/check-for-success {:match-pred (notification/request-type? :sync-create)})))

(defn edit-plan
  "Edits the given sync plan with optional new properties. See also
  create-sync-plan for more details."
  [name {:keys [new-name description interval start-date start-date-literal start-time-literal]
         :as m}]
  (nav/go-to ::named-plan-page {:sync-plan-name name})
  (let [[date time] (split-date m)]
    (common/in-place-edit {::plan-name-text new-name
                           ::plan-description-text description
                           ::plan-interval-select interval
                           ::plan-time-text time
                           ::plan-date-text date}))
  (notification/check-for-success {:match-pred (notification/request-type? :sync-update)}))

(defn schedule
  "Schedules the given list of products to be synced using the given
  sync plan name."
  [{:keys [products plan-name]}]
  (nav/go-to ::schedule-page)
  (doseq [product products]
    (browser click (schedule product)))
  (browser click (plan plan-name))
  (browser clickAndWait ::apply-schedule)
  (notification/check-for-success))  ;notif class is 'undefined' so
                                        ;don't match 

(defn current-plan
  "Returns a map of what sync plan a product is currently scheduled
  for. nil if UI says 'None'"
  [product-names]
  (nav/go-to ::schedule-page)
  (->> (for [product-name product-names]
       (browser getText (product-schedule product-name)))
     doall
     (replace {"None" nil})
     (zipmap product-names)))

(def messages {:ok "Sync complete."
               :fail "Error syncing!"})

(defn complete-status
  "Returns final status if complete. If sync is still in progress, not
  synced, or queued, returns nil."
  [product]
  (some #{(browser getText (provider-progress product))}
        (vals messages)))

(defn success? "Returns true if given sync result is a success."
  [res]
  (= res (:ok messages)))

(defn perform-sync
  "Syncs the given list of repositories. Also takes an optional
  timeout (in ms) of how long to wait for the sync to complete before
  throwing an error.  Default timeout is 2 minutes."
  [repos & [{:keys [timeout]}]]
  (nav/go-to ::status-page)
  (doseq [repo repos]
    (browser check (provider-checkbox repo)))
  (browser click ::synchronize-now)
  (browser sleep 10000)
  (zipmap repos (for [repo repos]
                  (sel/loop-with-timeout (or timeout 120000) []
                    (or (complete-status repo)
                        (do (Thread/sleep 10000)
                            (recur)))))))
