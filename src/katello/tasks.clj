(ns katello.tasks
  (:require [katello.locators :as locators]
            [com.redhat.qe.auto.navigate :as nav]
            [clojure.string :as string])
  (:use [com.redhat.qe.auto.selenium.selenium
         :only [connect browser ->browser fill-form fill-item
                loop-with-timeout]]
        [slingshot.slingshot :only [throw+ try+]]
        [com.redhat.qe.config :only [kw-to-text]]
        [com.redhat.qe.verify :only [verify-that]]
        [clojure.string :only [capitalize]])
  (:import [com.thoughtworks.selenium SeleniumException]
           [java.text SimpleDateFormat]))

(declare search)

(def library "Library")                 

;;var for synchronizing promotion calls, since only one can be done in
;;the system at a time.
(def promotion-lock nil)


(defmacro expecting-error
  "Execute forms, if error is caught matching selector, nil is
   returned, otherwise object to-throw is thrown."
  [selector & forms]
  `(try+ ~@forms
         (throw+ {:type :unexpected-success :expected ~selector})
         (catch ~selector e# nil)))

;;UI tasks
(defn timestamps
  "Infinite lazy sequence of timestamps in ms, starting with the current time."
  []
  (iterate inc (System/currentTimeMillis)))

(defn unique-names
  "Infinite lazy sequence of timestamped strings, uses s as the base string."
  [s]
  (for [t (timestamps)] (str s "-" t)))

(defn uniqueify "Get one unique name using s as the base string."
  [s]
  (first (unique-names s)))

(def known-errors
  {::validation-failed #"Validation [Ff]ailed"
   ::invalid-credentials #"incorrect username"
   ::promotion-already-in-progress #"Cannot promote.*while another changeset"})

(defn matching-error
  "Returns a keyword of known error, if the message matches any of
   them.  If no matches, returns :katello-error."
  [message]
  (or (some #(let [re (known-errors %)]
               (if (re-find re message) % false) )
            (keys known-errors))
      ::katello-error))

(defn- clear-all-notifications []
  (doseq [closebutton (map (comp locators/notification-close-index str)
                           (iterate inc 1))
          :while (browser isElementPresent closebutton)]
    (browser click closebutton)))

(defn success?
  "Returns true if the given notification is a 'success' type
  notification (aka green notification in the UI)."
  [notif]
  (-> notif :type (= :success)))

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
    (cond (not notif) (throw+
                       {:type ::no-success-message-error}
                       "Expected a success notification, but none appeared within the timeout period.")
          ((complement success?) notif) (let [errtype (matching-error msg)]
                                 (throw+ (assoc notif :type errtype) "Notification: %s" (pr-str %)))
          :else notif)))

(defn check-for-error
  "Waits for a notification up to the timeout (in ms), throws an
  exception if timeout is hit or error notification appears."
  [ & [timeout]]
  (try+ (check-for-success timeout)
        (catch [:type ::no-success-message-error] _)))

(defn verify-success
  "Calls task-fn and checks for a success message afterwards. If none
  is found, or an error notification appears, throws an exception."
  [task-fn]
  (let [notification (task-fn)]
    (verify-that (success? notification))))

(def navigate (nav/nav-fn locators/page-tree))

(defn fill-ajax-form
  "Fills in a web form and clicks the submit button. Only waits for
   ajax calls to complete. Items should be a map, where the keys are
   locators for form elements, and values are the values to fill in.
   Submit should be a locator for the form submit button."
  [items submit]
  (fill-form items submit (constantly nil)))

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
  (->browser (click :new-promotion-changeset)
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
      (browser click (locators/promotion-add-content-item item)))
    (browser sleep 5000)))  ;;sleep to wait for browser->server comms to update changeset
;;can't navigate away until that's done

(defn- async-notification [& [timeout]]
  (Thread/sleep 3000)
  (loop-with-timeout (or timeout 180000) []
    (or (notification)
        (do (browser refresh)
            (recur)))))

(defn promote-changeset [changeset-name {:keys [from-env to-env timeout-ms]}]
  (let [nav-to-cs (fn [] (navigate :named-changeset-promotions-page
                                  {:env-name from-env
                                   :next-env-name to-env
                                   :changeset-name changeset-name}))]
    (nav-to-cs)
    (locking #'promotion-lock
      (browser click :review-for-promotion)
      ;;for the submission
      (loop-with-timeout 600000 []
        (when-not (try+ (browser click :promote-to-next-environment)
                        (check-for-success)
                        (catch [:type ::promotion-already-in-progress] _
                          (nav-to-cs)))
          (Thread/sleep 30000)
          (recur)))
      ;;for confirmation
      (loop-with-timeout (or timeout-ms 120000) [status ""]
        (case status
          "Promoted" status
          "Promotion Failed" (throw+ {:type :promotion-failed
                                      :changeset changeset-name
                                      :from-env from-env
                                      :to-env to-env})
          (do (Thread/sleep 2000)
              (recur (browser getText
                              (locators/changeset-status changeset-name))))))
      ;;wait for async success notif
      (async-notification))))

(defn promote-content [from-env to-env content]
  (let [changeset (uniqueify "changeset")]
    (create-changeset from-env to-env changeset)
    (add-to-changeset changeset from-env to-env content)
    (promote-changeset changeset {:from-env from-env
                                        :to-env to-env
                                        :timeout-ms 300000})))

(defn extract-left-pane-list [loc]
  "Extract data from the left pane, accepts locator as argument
   for example, extract-left-pane-list locators/left-pane-field-list"
  (let [elems (for [index (iterate inc 1)]
                (loc (str index)))]
    (take-while identity (for [elem elems]
                           (try (browser getText elem)
                                (catch SeleniumException e nil))))))

(defn validate-search [entity-type &  [{:keys [criteria scope] :as search-opts}]]
  "Validate a search request.  entity-type can be anything that has a
   support search, :orgs, :users etc...  criteria is something you are
   searching for.  scope is currently not implemented."
  (search entity-type  search-opts)
  (if-not (every? (fn [s] (.contains s criteria))
                  (extract-left-pane-list locators/left-pane-field-list))
    (throw+ {:type :search-results-inaccurate :msg "Not all search results match criteria."})))

(defn extract-content []
  (let [elems (for [index (iterate inc 1)]
                (locators/promotion-content-item-n (str index)))
        retrieve (fn [elem]
                   (try (browser getText elem)
                        (catch Exception e nil)))]
    (->> (map retrieve elems) (take-while identity) set)))

(defn environment-content [env]
  (navigate :named-environment-promotions-page {:env-name env
                                                :next-env-name nil})
  (let [categories [:products :templates]]
    (zipmap categories
            (doall (for [category categories]
                     (do (browser click (-> category name (str "-category") keyword))
                         (browser sleep 2000)
                         (let [result (extract-content)]
                           (browser click :promotion-eligible-home)
                           result)))))))

(defn ^{:TODO "finish me"} change-set-content [env]
  (navigate :named-environment-promotions-page {:env-name env}))

(defn environment-has-content?
  "If all the content is present in the given environment, returns true."
  [env content]
  (navigate :named-environment-promotions-page {:env-name env :next-env-name ""})
  (every? true?
          (flatten
           (for [category (keys content)]
             (do (browser click (-> category name (str "-category") keyword))
                 (for [item (content category)]
                   (try (do (browser isVisible
                                     (locators/promotion-add-content-item item))
                            true)
                        (catch Exception e false))))))))

(defn create-organization [name & [{:keys [description]}]]
  (navigate :new-organization-page)
  (fill-ajax-form {:org-name-text name
                   :org-description-text description}
                  :create-organization)
  (check-for-success))

(defn delete-organization [org-name & [{:keys [confirm-timeout-ms]}]]
  (navigate :named-organization-page {:org-name org-name})
  (browser click :remove-organization)
  (browser click :confirmation-yes)
  (check-for-success) ;queueing success
  (async-notification)  ;for actual delete
  )

(defn create-environment
  [name {:keys [org-name description prior-env]}]
  (navigate :new-environment-page {:org-name org-name})
  (fill-ajax-form {:env-name-text name
                   :env-description-text description
                   :prior-environment prior-env}
                  :create-environment)
  (check-for-success))

(defn delete-environment [env-name {:keys [org-name]}]
  (navigate :named-environment-page {:org-name org-name
                                     :env-name env-name})
  (if (browser isElementPresent :remove-environment)
    (browser click :remove-environment)
    (throw+ {:type :env-cant-be-deleted :env-name env-name}))
  (browser click :confirmation-yes)
  (check-for-success))

(defn edit-organization [org-name & {:keys [description]}]
  (navigate :named-organization-page {:org-name org-name})
  (in-place-edit {:org-description-text-edit description})
  (check-for-success))

(defn edit-environment [env-name {:keys [org-name new-name description prior]}]
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

(defn create-provider [{:keys [name description]}]
  (navigate :new-provider-page)
  (fill-ajax-form {:provider-name-text name
                   :provider-description-text description}
                  :provider-create-save)
  (check-for-success))

(defn add-product [{:keys [provider-name name description]}]
  (navigate :provider-products-repos-page {:provider-name provider-name})
  (browser click :add-product)
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
  (browser click (locators/add-repository product-name))
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

(defn edit-provider [{:keys [name new-name description]}]
  (navigate :provider-details-page {:provider-name name})
  (in-place-edit {:provider-name-text new-name
                  :provider-description-text description})
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
      (comment "removed on suspicion the success notif disappears sometimes before it can be read"
               (check-for-success))))

(defn current-user
  "Returns the name of the currently logged in user, or nil if logged out."
  []
  (when (logged-in?)
    (browser getText :account)))

(defn ensure-current-user
  "If username is already logged in, does nothing. Otherwise logs in
  with given username and password."
  [username password]
  (if-not (= (current-user) username)
    (login username password)))

(defn create-user [username {:keys [password password-confirm email default-org default-env]}]
  (navigate :users-tab)
  (browser click :new-user)
  (when default-env
    (locators/select-environment-widget default-env))
  (fill-ajax-form {:new-user-username-text username
                   :new-user-password-text password
                   :new-user-confirm-text (or password-confirm password)
                   :new-user-email email
                   :new-user-default-org default-org}
                  :save-user)
  (check-for-success))

(defn delete-user [username]
  (navigate :named-user-page {:username username})
  (browser click :remove-user)
  (browser click :confirmation-yes)
  (check-for-success))
  
(defn edit-user [username {:keys [inline-help clear-disabled-helptips new-password new-password-confirm new-email]}]
  (navigate :named-user-page {:username username})
  (when new-password
    (browser setText :change-password-text new-password)
    (browser setText :confirm-password-text (or new-password-confirm new-password))

    ;;hack alert - force the page to check the passwords (selenium
    ;;doesn't fire the event by itself
    (browser getEval "window.KT.user_page.verifyPassword();")

    (when (browser isElementPresent :password-conflict)
      (throw+ {:type :password-mismatch :msg "Passwords do not match"}))
    (browser click :save-user-edit) 
    (check-for-success))
  (when new-email
    (in-place-edit {:user-email-text new-email})
    (check-for-success)))

(defn search [entity-type & [{:keys [criteria scope]}]]
  "Search for criteria in entity-type, scope not yet implemented.
   check for error with a 2s timeout.  In this case error is a 
   error jnotify object."
  (navigate (entity-type {:users :users-tab 
                          :orgs :organizations-tab}))
  (browser type :search-bar criteria)
  (browser click :search-submit)
  (check-for-error 2000))

(defn create-role [name & [{:keys [description]}]]
  (navigate :roles-tab)
  (browser click :new-role)
  (fill-ajax-form {:new-role-name-text name
                   :new-role-description-text description}
                  :save-role)
  (check-for-success))

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
                  (browser click :next)
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

(def sync-messages {:ok "Sync complete."
                    :fail "Error syncing!"})

(defn sync-complete-status
  "Returns final status if complete.  If sync is still in progress or queued, returns nil."
  [product]
  (some #{(browser getText (locators/provider-sync-progress product))}
        (vals sync-messages)))

(defn sync-success? "Returns true if given sync result is a success."
  [res]
  (= res (:ok sync-messages)))


(defn sync-repos [repos & [{:keys [timeout]}]]
  (navigate :sync-status-page)
  (doseq [repo repos]
    (browser check (locators/provider-sync-checkbox repo)))
  (browser click :synchronize-now)
  (browser sleep 10000)
  (zipmap repos (for [repo repos]
                     (loop-with-timeout (or timeout 120000) []
                       (or (sync-complete-status repo)
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
  (doseq [product products]
    (browser check (locators/subscription-checkbox product)))
  (browser click :subscribe)
  (check-for-success))

(def syncplan-dateformat (SimpleDateFormat. "MM/dd/yyyy"))
(def syncplan-timeformat (SimpleDateFormat. "hh:mm aa"))
(defn date-str [d] (.format syncplan-dateformat d))
(defn time-str [d] (.format syncplan-timeformat d))

(defn- split-date [{:keys [start-date start-date-literal start-time-literal]}]
  (list (if start-date (date-str start-date) start-date-literal)
        (if start-date (time-str start-date) start-time-literal)))

(defn create-sync-plan [{:keys [name description interval start-date
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

(defn edit-sync-plan [name {:keys [new-name description interval start-date
                                   start-date-literal start-time-literal] :as m}]
  (navigate :named-sync-plan-page {:sync-plan-name name})
  (let [[date time] (split-date m)]
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
  (browser click (locators/sync-plan plan-name))
  (browser clickAndWait :apply-sync-schedule )
  (check-for-success))

(defn current-sync-plan
  "Returns a map of what sync plan a product is currently scheduled for.  nil if UI says 'None'"
  [product-names]
  (navigate :sync-schedule-page)
  (zipmap product-names
          (replace {"None" nil}
                   (doall (for [product-name product-names]
                            (browser getText (locators/product-schedule product-name)))))))

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

(defn upload-subscription-manifest [file-path & [{:keys [repository-url]}]]
  (navigate :redhat-provider-tab)
  (when repository-url
    (in-place-edit {:redhat-provider-repository-url-text repository-url}))
  (fill-form {:choose-file file-path}
             :upload
             (fn [] (browser waitForPageToLoad "300000")))
  (check-for-success))

(defn manifest-already-uploaded? []
  (navigate :redhat-provider-tab)
  (browser isElementPresent :subscriptions-items))

(defn create-template [{:keys [name description]}]
  (navigate :new-system-template-page)
  (fill-ajax-form {:template-name-text name
                   :template-description-text description}
                  :save-new-template)
  (check-for-success))

(defn add-to-template "Adds content to a template"
  [name content]
  (comment "content is like:" [{:product "prod3"
                                :packages ["rpm1" "rpm2"]}
                               {:product "prod6"
                                :repositories ["x86_64"]}])
  (navigate :named-system-template-page {:template-name name})
  (let [add-item (fn [item] (locators/toggle locators/template-toggler item true))]
    (doseq [group content]
      (let [category-keyword (-> group (dissoc :product) keys first)
            category-name (kw-to-text category-keyword capitalize)]
        (->browser
         (getEval "window.onbeforeunload = function(){};") ;circumvent popup
         (sleep 2000)
         (click (locators/template-product (:product group)))
         (sleep 2000)
         (click (locators/template-eligible-category category-name)))
        (doall (map add-item (group category-keyword)))
        (browser click :template-eligible-home)))
    (browser click :save-template)
    (check-for-success)))

(defn switch-org "Switch to the given organization in the UI." [org-name]
  (browser click :org-switcher)
  (browser clickAndWait (locators/org-switcher org-name)))

(defn enable-redhat-repositories
  "Enable the given list of repos in the current org"
  [repos]
  (navigate :redhat-provider-tab)
  (browser click :enable-repositories-tab)
  (doseq [repo repos]
    (browser check (locators/repo-enable-checkbox repo))))

(defn current-org []
  "return the currently active org shown in the org switcher."
  (browser getText :active-org))

(defmacro with-org [org-name & body]
  "Switch to org-name, execute body, and then switch back to current
   org, whether there was an exception or not."
  `(let [curr-org# (current-org)]
     (try (switch-org ~org-name)
          ~@body
          (finally (switch-org curr-org#)))))