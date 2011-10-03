(ns katello.tasks
  (:require [katello.locators :as locators]
            [katello.api-tasks :as api]
            [com.redhat.qe.auto.navigate :as nav]
            [clojure.string :as string])
  (:use [com.redhat.qe.auto.selenium.selenium
         :only [connect browser ->browser fill-form fill-item
                loop-with-timeout]]
        [error.handler :only [raise]]
        [com.redhat.qe.verify :only [verify-that]]
        [test.tree :only [print-meta]])
  (:import [com.thoughtworks.selenium SeleniumException]
           [java.text SimpleDateFormat]))

;;tasks
(defn timestamp
  "Returns a string with timestamp (time in millis since 1970)
   appended onto the end.  If optional n is specified, returns a list
   of n timestamped strings, where the millis is incremented by one
   for each item."
  ([s] (str s "-" (System/currentTimeMillis)))
  ([s n] (take n (map #(str s "-" %) (iterate inc (System/currentTimeMillis))))))

(def uniqueify timestamp) ;;alias for timestamp

(def known-errors
  {:validation-failed #"Validation [Ff]ailed"
   :invalid-credentials #"incorrect username"})

(defn matching-error
  "Returns a keyword of known error, if the message matches any of
   them.  If no matches, returns :katello-error."
  [message]
  (or (some #(let [re (known-errors %)]
               (if (re-find re message) % false) )
            (keys known-errors))
      :katello-error))

(defn- clear-all-notifications []
  (doseq [closebutton (map (comp locators/notification-close-index str)
                           (iterate inc 1))
          :while (browser isElementPresent closebutton)]
    (browser click closebutton)))

(def success? (with-meta (fn [notif]
                           (-> notif :type (= :success)))
                (print-meta 'success?)))

(defn notification
  "Gets the notification from the page, returns a map object
   representing the notification (or nil if no notification is present
   within a built-in timeout period)."
  [ & [max-wait-ms]]
  (try (browser waitForElement :notification (str (or max-wait-ms 15000)))
       (let [msg (browser getText :notification)
             classattr ((into {} (browser getAttributes :notification)) "class")
             type ({"jnotify-notification-error" :error
                    "jnotify-notification-message" :message
                    "jnotify-notification-success" :success}
                   (-> (string/split classattr #" ") second))] 
         (clear-all-notifications)
         {:type type :msg msg})
       (catch SeleniumException e nil))) 

(defn check-for-success
  "Gets any notification from the UI, if there is none, or it's not a
   success notification, raise an exception.  Otherwise return the
   type and text of the message."
  [ & [max-wait-ms]]
  (let [notif (notification max-wait-ms)
        msg (:msg notif)]
    (cond (not notif) (raise
                       {:type :no-success-message-error
                        :msg "Expected a result message, but none is present on page."})
          ((complement success?) notif) (let [errtype (matching-error msg)] 
                                          (raise (assoc notif :type errtype)))
          :else notif)))

(defn verify-success [task-fn]
  (let [notification (task-fn)]
    (verify-that (success? notification))))

(def navigate (nav/nav-fn locators/page-tree))

(defn fill-ajax-form [items submit]
  (fill-form items submit #(browser sleep 300)))

(defn activate-in-place
  "For an in-place edit input, switch it from read-only to editing
   mode. Takes the locator of the input in editing mode as an
   argument."
  [loc]
  (browser click (locators/inactive-edit-field loc)))

(defn in-place-edit
  "Fill out a form that uses in-place editing.  Takes a map of
   locators to values.  The locators given should be for the
   editing-mode version of the input, it will be activated from its
   read-only state automatically."
  [items]
  (doseq [[loc val] items]
    (if-not (nil? val)
      (do (activate-in-place loc)
          (fill-item loc val)
          (browser click :save-inplace-edit)))))

(defn create-changeset [env-name next-env-name changeset-name]
  (navigate :named-environment-promotions-page {:env-name env-name
                                                :next-env-name next-env-name})
  (->browser (click :new-changeset)
             (waitForElement :changeset-name-text "30000")
             (setText :changeset-name-text changeset-name)
             (click :save-changeset))
  (check-for-success))

(defn add-to-changeset [changeset-name from-env to-env content]
  (navigate :named-changeset-promotions-page {:env-name from-env
                                              :next-env-name to-env
                                              :changeset-name changeset-name})
  (doseq [category (keys content)]
    (browser click (-> category name (str "-category") keyword))
    (doseq [item (content category)]
      (->browser (waitAndClick (locators/promotion-add-content-item item) "10000")
                 (waitForElement (locators/promotion-remove-content-item item) "10000")
                 (sleep 1000)))))

(defn promote-changeset [changeset-name from-env to-env]
  (navigate :named-changeset-promotions-page
            {:env-name from-env
             :next-env-name to-env
             :changeset-name changeset-name})
  (browser waitAndClick :review-for-promotion "10000")
  (browser waitAndClick :promote-to-next-environment "10000")
  (check-for-success) ;;for the submission
  (loop-with-timeout 120000 [status ""]
    (if (= status "Promoted")
      status
      (do (Thread/sleep 1000)
          (recur (browser getText (locators/changeset-status changeset-name)))))))

(defn extract-content []
  (let [elems (for [index (iterate inc 1)]
                (locators/promotion-content-item-n (str index)))
        retrieve (fn [elem]
                   (try (-> (browser getText elem) .trim (string/replace #" +\S+$" ""))
                        (catch Exception e nil)))]
    (->> (map retrieve elems) (take-while identity) set))) 

(defn environment-content [env]
  (navigate :named-environment-promotions-page {:env-name env
                                                :next-env-name nil})
  (let [categories [:products]]
    (into {}
          (for [category categories]
            (do (browser click (-> category name (str "-category") keyword))
                (browser sleep 2000)
                [category (extract-content)])))))

(defn ^{:TODO "finish me"} change-set-content [env]
  (navigate :named-environment-promotions-page {:env-name env}))

(defn environment-has-content?
  "If all the content is present in the given environment, returns true."
  [env content]
  (navigate :named-environment-promotions-page {:env-name env})
  (every? true?
          (flatten
           (for [category (keys content)]
             (do (browser click (-> category name (str "-category") keyword))
                 (for [item (content category)]
                   (try (do (browser waitForElement
                                     (locators/promotion-add-content-item item) "10000")
                            true)
                        (catch Exception e false))))))))

(defn create-organization [name description]
  (navigate :new-organization-page)
  (fill-ajax-form {:org-name-text name
              :org-description-text description}
             :create-organization)
  (check-for-success))

(defn delete-organization [org-name]
  (navigate :named-organization-page {:org-name org-name})
  (browser click :remove-organization)
  (browser click :confirmation-yes)
  (check-for-success))

(defn create-environment
  [org name description & [prior-env]]
  (navigate :new-environment-page {:org-name org})
  (fill-ajax-form {:env-name-text name
                   :env-description-text description
                   :prior-environment prior-env}
                  :create-environment)
  (check-for-success))

(defn delete-environment [org-name env-name]
  (navigate :named-environment-page {:org-name org-name
                                     :env-name env-name})
  (browser click :remove-environment)
  (browser click :confirmation-yes)
  (check-for-success))

(defn edit-organization [org-name & {:keys [description]}]
  (navigate :named-organization-page {:org-name org-name})
  (in-place-edit {:org-description-text-edit description})
  (check-for-success))

(defn edit-environment [org-name env-name & {:keys [new-name description prior]}]
  (navigate :named-environment-page {:org-name org-name
                                     :env-name env-name})
  (in-place-edit {:env-name-text-edit new-name
                  :env-description-text-edit description
                  :env-prior-select-edit prior})
  (check-for-success))


(defn environment-other-possible-priors
  "Returns a set of priors that are selectable for the given
   environment (will not include the currently selected prior)."
   [org-name env-name]
  (navigate :named-environment-page {:org-name org-name
                                     :env-name env-name})
  (activate-in-place :env-prior-select-edit)
  (set (browser getSelectOptions :env-prior-select-edit)))

(defn create-provider [{:keys [name description type repo-url]}]
  (let [types {:redhat "Red Hat"
               :custom "Custom"}]
    (assert (some #{type} (keys types)))
    (navigate :new-provider-page)
    (fill-ajax-form {:provider-name-text name
                     :provider-description-text description
                     :provider-repository-url-text (if (= type :redhat)
                                               repo-url nil)
                     :provider-type-list (types type)}
                    :provider-create-save)
    (check-for-success)))

(defn add-product [{:keys [provider-name name description]}]
  (navigate :provider-products-repos-page {:provider-name provider-name})
  (browser click :add-product)
  (browser waitForVisible :product-name-text "10000")
  (fill-ajax-form {:product-name-text name
                   :product-description-text description}
                  :create-product)
  (check-for-success))

(defn delete-product [{:keys [name provider-name]}]
  (navigate :named-product-page {:provider-name provider-name
                                 :product-name name})
  (browser click :remove-product)
  (browser click :confirmation-yes)
  (check-for-success))

(defn add-repo [{:keys [provider-name product-name name url]}]
  (navigate :provider-products-repos-page {:provider-name provider-name})
  (let [add-repo-button (locators/add-repository product-name)]
    (->browser (click (locators/product-expand product-name))
               (waitForVisible add-repo-button "5000")
               (click add-repo-button)
               (waitForElement :repo-name-text "10000")))
  (fill-ajax-form {:repo-name-text name
                   :repo-url-text url}
                  :save-repository)
  (check-for-success))

(defn delete-repo [{:keys [name provider-name product-name]}]
  (navigate :named-repo-page {:provider-name provider-name
                              :product-name product-name
                              :repo-name name})
  (browser click :remove-repository)
  (browser click :confirmation-yes)
  (check-for-success))

(defn delete-provider [name]
  (navigate :named-provider-page {:provider-name name})
  (browser click :remove-provider)
  (browser click :confirmation-yes)
  (check-for-success))

(defn edit-provider [{:keys [name new-name description repo-url]}]
  (navigate :named-provider-page {:provider-name name})
  (in-place-edit {:provider-name-text new-name
                  :provider-description-text description
                  :provider-repository-url-text repo-url})
  (check-for-success))

(defn logged-in? []
  (browser isElementPresent :log-out))

(defn logged-out? []
  (browser isElementPresent :log-in))

(defn logout []
  (if-not (logged-out?)
    (browser clickAndWait :log-out)))

(defn login [username password]
  (when (logged-in?)
    (logout))
  (do (fill-form {:username-text username
                  :password-text password}
                 :log-in)
      (check-for-success)))

(defn current-user []
  (if (logged-in?)
    (browser getText :account)))

(defn ensure-current-user [username password]
  (if-not (= (current-user) username)
    (login username password)))

(defn create-user [username {:keys [password password-confirm]}] 
  (navigate :users-tab)
  (->browser (click :new-user)
             (waitForElement :new-user-username-text "15000"))
  (fill-ajax-form {:new-user-username-text username
              :new-user-password-text password
              :new-user-confirm-text (or password-confirm password)}
             :save-user)
  (check-for-success))

(defn delete-user [username]
  (navigate :named-user-page {:username username})
  (browser click :remove-user)
  (browser click :confirmation-yes)
  (check-for-success))

(defn edit-user [username {:keys [inline-help clear-disabled-helptips new-password]}]
  (navigate :named-user-page {:username username})
  (fill-ajax-form {:enable-inline-help-checkbox inline-help
              :clear-disabled-helptips clear-disabled-helptips
              :change-password-text new-password
              :confirm-password-text new-password}
             :save-user-edit)
  (check-for-success))

(defn create-role [name]
  (navigate :roles-tab)
  (->browser (click :new-role)
             (waitForElement :new-role-name-text "7500")) 
  (fill-ajax-form {:new-role-name-text name} :save-role))

(defn assign-role [{:keys [user roles]}]
  (navigate :user-roles-permissions-page {:username user})
  (doseq [role roles]
    (browser click (locators/plus-icon role)))
  (browser click :save-roles)
  (check-for-success))

(defn edit-role [name {:keys [add-permissions remove-permissions users]}]
  (let [nav (fn [page] (navigate page {:role-name name}))
        each-org (fn [all-perms perms-fn]
                   (when all-perms
                     (nav :named-role-permissions-page)
                     (doseq [{:keys [org permissions]} all-perms]
                      (->browser (click (locators/permission-org org))
                                 (sleep 1000))
                      (perms-fn permissions)
                      (browser click :role-permissions))))] ;;go back up to choose next org
    (when users
      (nav :named-role-users-page)
      (doseq [user users]
        (locators/toggle locators/user-role-toggler user true)))
    (each-org remove-permissions
              (fn [permissions]
                (doseq [permission permissions]
                  (browser click (locators/user-role-toggler permission false))
                  (check-for-success)
                  (browser sleep 5000)))) 
    (each-org add-permissions
              (fn [permissions]
                (doseq [{:keys [name description resource-type verbs tags]} permissions]
                  (browser click :add-permission)
                  (browser select :permission-resource-type-select resource-type)
                  (doseq [verb verbs]
                    (browser addSelection :permission-verb-select verb))
                  (browser click :next)
                  (doseq [tag tags]
                    (browser addSelection :permission-tag-select tag))
                  (fill-ajax-form {:permission-name-text name
                                   :permission-description-text description}
                                  :save-permission))
                (check-for-success)))))

(defn remove-role [name]
  (navigate :named-role-page {:role-name name})
  (browser click :remove-role)
  (browser click :confirmation-yes)
  (check-for-success))

(defn sync-complete-status
  "Returns final status if complete.  If sync is still in progress or queued, returns nil."
  [product]
  (some #{(browser getText (locators/provider-sync-progress product))} 
                ["Error syncing!" "Sync complete."]))

(defn sync-products [products timeout]
  (navigate :sync-management-page)
  (doseq [product products]
    (browser check (locators/provider-sync-checkbox product)))
  (browser click :synchronize-now)
  (browser sleep 10000)
  (zipmap products (for [product products]
                     (loop-with-timeout timeout []
                       (or (sync-complete-status product)
                           (do (Thread/sleep 10000)
                               (recur)))))))

(defn edit-system [name & {:keys [new-name description location]}]
  (navigate :named-systems-page {:system-name name})
  (in-place-edit {:system-name-text-edit new-name
                  :system-description-text-edit description
                  :system-location-text-edit location})
  (check-for-success))

(defn subscribe-system [{:keys [system-name products]}]
  (navigate :system-subscriptions-page {:system-name system-name})
  (browser sleep 3000)
  (doseq [product products]
    (browser check (locators/subscription-checkbox product)))
  (browser click :subscribe)
  (check-for-success))

(defn split-date [date]
  (try (doall (map (fn [fmt] (.format fmt date))
                   [(SimpleDateFormat. "MM/dd/yyyy")
                    (SimpleDateFormat. "hh:mm aa")]))
       (catch Exception e [nil nil])))

(defn create-sync-plan [{:keys [name description interval start-date]}]
  (navigate :new-sync-plan-page)
  (let [[date time] (split-date start-date)]
    (fill-ajax-form {:sync-plan-name-text name
                     :sync-plan-description-text description
                     :sync-plan-interval-select interval
                     :sync-plan-time-text time
                     :sync-plan-date-text date}
                    :save-sync-plan)
    (check-for-success)))

(defn edit-sync-plan [name {:keys [new-name description interval start-date]}]
  (navigate :named-sync-plan-page {:sync-plan-name name})
  (let [[date time] (split-date start-date)]
    (in-place-edit {:sync-plan-name-text new-name 
                    :sync-plan-description-text description
                    :sync-plan-interval-select interval
                    :sync-plan-time-text time
                    :sync-plan-date-text date}))
  (check-for-success))

(defn sync-schedule [{:keys [products plan-name]}]
  (navigate :sync-schedule-page)
  (doseq [product products]
    (browser click (locators/schedule product)))
  (browser click (locators/schedule plan-name))
  (browser clickAndWait :apply-sync-schedule )
  (check-for-success))

(defn create-activation-key [{:keys [name description environment system-template] :as m}]
  (navigate :new-activation-key-page)
  (browser click (locators/environment-link environment))
  (fill-ajax-form {:activation-key-name-text name
                   :activation-key-description-text description
                   :activation-key-template-select system-template}
                  :save-activation-key)
  (check-for-success))

(defn delete-activation-key [name]
  (navigate :named-activation-key-page {:activation-key-name name})
  (browser click :remove-activation-key)
  (browser click :confirmation-yes)
  (check-for-success))

(defn upload-subscription-manifest [{:keys [provider-name file-path]}]
  (navigate :provider-subscriptions-page {:provider-name provider-name})
  (fill-ajax-form {:choose-file file-path}
                  :upload)
  (check-for-success))
