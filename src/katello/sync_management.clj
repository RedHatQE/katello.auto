(ns katello.sync-management
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            [clojure.data :as data]
            [katello :as kt]
            (katello [navigation :as nav] 
                     [notifications :as notification] 
                     [ui-common :as common]
                     [tasks :as tasks]
                     [ui :as ui]
                     [rest :as rest]))
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
  plan-link         "//div[@id='plans']//div[normalize-space(.)='%s']"
  schedule-item     "//div[normalize-space(.)='%s']"})

;; Nav

(nav/defpages (common/pages)
  [::plans-page
   [::named-plan-page [sync-plan]
    (nav/choose-left-pane sync-plan)]
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
           start-date-literal start-time-literal org] :as plan}]
  (nav/go-to ::new-plan-page {:org org})
  (let [[date time] (split-date plan)]
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
  [plan updated]
  (nav/go-to plan)
  (let [[removed {:keys [name description interval]
                  :as added}] (data/diff plan updated)
        [date time] (split-date added)]
    (common/in-place-edit {::plan-name-text name
                           ::plan-description-text description
                           ::plan-interval-select interval
                           ::plan-time-text time
                           ::plan-date-text date}))
  (notification/check-for-success {:match-pred (notification/request-type? :sync-update)}))

(extend katello.SyncPlan
  ui/CRUD {:create create-plan
           :update* edit-plan}
  
  tasks/Uniqueable {:uniques #(for [s (tasks/timestamps)]
                                (assoc (tasks/stamp-entity % s)
                                  :start-time (java.util.Date.)))}
  
  nav/Destination {:go-to #(nav/go-to ::named-plan-page {:sync-plan %1
                                                         :org (:org %1)})})

(defn schedule
  "Schedules the given list of products to be synced using the given
  sync plan name."
  [{:keys [products plan]}]
  (nav/go-to ::schedule-page {:org (:org plan)})
  (doseq [product products]
    (browser click (schedule-item (:name product))))
  (browser click (plan-link (:name plan)))
  (browser clickAndWait ::apply-schedule)
  (notification/check-for-success))  ;notif class is 'undefined' so
                                        ;don't match 

(defn current-plan
  "Returns a map of what sync plan a product is currently scheduled
  for. nil if UI says 'None'"
  [products]
  (nav/go-to ::schedule-page {:org (-> products first :provider :org)})
  (->> (for [product products]
         (browser getText (product-schedule (:name product))))
       doall
       (replace {"None" nil})
       (zipmap products)))

(def messages {:ok "Sync complete."
               :fail "Error syncing!"})

(defn complete-status
  "Returns final status if complete. If sync is still in progress, not
  synced, or queued, returns nil."
  [product-or-repo]
  (some #{(browser getText (provider-progress (:name product-or-repo)))}
        (vals messages)))

(defn success? "Returns true if given sync result is a success."
  [res]
  (= res (:ok messages)))

(defn perform-sync
  "Syncs the given list of repositories. Also takes an optional
  timeout (in ms) of how long to wait for the sync to complete before
  throwing an error.  Default timeout is 2 minutes."
  [repos & [{:keys [timeout]}]]
  (nav/go-to ::status-page {:org (-> repos first kt/org)})
  (doseq [repo repos]
    (browser check (provider-checkbox (:name repo))))
  (browser click ::synchronize-now)
  (browser sleep 10000)
  (zipmap repos (for [repo repos]
                  (sel/loop-with-timeout (or timeout 120000) []
                    (or (complete-status repo)
                        (do (Thread/sleep 10000)
                            (recur)))))))

(defn create-all-and-sync
  "Creates all the given repos, including their products and
  providers.  All must not exist already."
  [repos]
  (let [distinct-parents (fn [& ks]
                           (->> repos
                                (map (apply comp (reverse ks)))
                                (apply hash-set)))
        ;; orgs (distinct-parents :product :provider :org)
        providers (distinct-parents :product :provider)
        products (distinct-parents :product)]
    (rest/create-all (concat providers products repos))
    (perform-sync (filter (complement :unsyncable) repos))))
