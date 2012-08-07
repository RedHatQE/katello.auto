(ns katello.ui-tasks
  (:require [katello.locators :as locators]
            [ui.navigate :as nav]
            [clojure.string :as string])
  (:use [com.redhat.qe.auto.selenium.selenium
         :only [connect browser ->browser fill-form fill-item
                loop-with-timeout]]
        katello.tasks
        [katello.conf :only [config]]
        [katello.api-tasks :only [when-katello when-headpin]]
        [slingshot.slingshot :only [throw+ try+]]
        [tools.verify :only [verify-that]]
        [clojure.string :only [capitalize]]
        [clojure.set :only [union]])
  (:import [com.thoughtworks.selenium SeleniumException]
           [java.text SimpleDateFormat]))

(declare search)


;;UI tasks


(def ^{:doc "All the different validation error messages that Katello
             can throw. The keys are keywords that can be used to
             refer to this type of error, and the values are regexes
             that match the error notification message in the UI."}
  validation-errors
  (let [errors {::name-taken-error                    #"(Username|Name) has already been taken"
                ::name-no-leading-trailing-whitespace #"Name must not contain leading or trailing white space"
                ::name-must-not-contain-characters    #"Name cannot contain characters other than"
                ::name-must-be-unique-within-org      #"Name must be unique within one organization" 
                ::repository-url-invalid              #"Repository url is invalid"
                ::start-date-time-cant-be-blank       #"Date and Time can't be blank"
                ::password-too-short                  #"Password must be at least"
                ::product-must-be-unique-in-org       #"Products within an organization must have unique name"
                ::repository-url-cant-be-blank        #"Repository url can't be blank",
                ::name-cant-be-blank                  #"Name can't be blank"}]
    (doseq [e (keys errors)]
      (derive e ::validation-error))  ; validation-error is a parent type
                                      ; so you can catch that type to
                                      ; mean "any" validation error.
    errors))

(def ^{:doc "A mapping of known errors in Katello. This helps
  automation throw and catch the right type of exception interally,
  taking UI error messages and mapping them to internal error types."}
  known-errors
  (let [errors {::invalid-credentials                   #"incorrect username"
                ::promotion-already-in-progress         #"Cannot promote.*while another changeset"
                ::import-older-than-existing-data       #"Import is older than existing data"
                ::distributor-has-already-been-imported #"This distributor has already been imported by another owner"}]
    (doseq [e (conj (keys errors) ::validation-error)]
      (derive e ::katello-error))
    (merge errors validation-errors)))

(defn matching-errors
  "Returns a set of matching known errors"
  [notifSet]
  (->> known-errors
     (filter (fn [[_ v]] (some not-empty (for [msg (map :msg notifSet)] (re-find v msg)))))
     (map key)
     set))

(defn- clear-all-notifications []
  (try
    (doseq [i (iterate inc 1)]
      (browser click (locators/notification-close-index (str i))))
    (catch SeleniumException _ nil)))

(def success?
  "Returns true if the given notification is a 'success' type
  notification (aka green notification in the UI)."
  ^{:type :serializable.fn/serializable-fn
    :serializable.fn/source 'success?}
  (fn [notif]
    (and notif (-> notif :type (= :success)))))

(defn wait-for-notification-gone
  "Waits for a notification to disappear within the timeout period. If no
   notification is present, the function returns immediately. The default
   timeout is 3 seconds."
  [ & [max-wait-ms]]
  (loop-with-timeout (or max-wait-ms 3000) []
    (if ( browser isElementPresent :notification)
      (do (Thread/sleep 100) (recur)))
    (throw+ {:type :wait-for-notification-gone-timeout} 
            "Notification did not disappear within the specified timeout")))



(defn notifications
  "Gets all notifications from the page, returns a list of maps
   representing the notifications. Waits for timeout-ms for at least
   one notification to appear. Does not do any extra waiting after the
   first notification is detected. Clears all the notifications from
   the UI. Default timeout is 15 seconds."
  [ & [{:keys [timeout-ms] :or {timeout-ms 2000}}]]
  (try
    (browser waitForElement :notification (str timeout-ms))
    (let [notif-at-index (fn [idx]
                           (let [notif (locators/notification-index (str idx))]
                             (when (browser isElementPresent notif)
                               (let [msg (browser getText notif)
                                     classattr ((into {}
                                                       (browser getAttributes notif))
                                                "class")
                                     type (->> classattr (re-find #"jnotify-notification-(\w+)") second keyword)]
                                 {:type type :msg msg}))))]
      (doall (take-while identity (map notif-at-index (iterate inc 1)))))
    (catch SeleniumException e '())
    (finally (clear-all-notifications))))

(defn errtype
  "Creates a predicate that matches a caught UI error of the given
   type (see known-errors). Use this predicate in a slingshot 'catch'
   statement. If any of the error types match (in case of multiple
   validation errors), the predicate will return true. Uses isa? for
   comparison, so hierarchies will be checked.
   example (try+ (dothat) (catch (errtype ::validation-error) _ nil))"
  [t]
  (with-meta
    (fn [e] (some #(isa? % t) (:types e)))
    {:type :serializable.fn/serializable-fn
     :serializable.fn/source `(errtype ~t)}) )

(defn check-for-success
  "Returns information about a success notification from the UI. Will
   wait for a success notification until timeout occurs, collecting
   any failure notifications captured in that time. If there are no
   notifications or any failure notifications are captured, an
   exception is thrown containing information about all captured
   notifications (including a success notification if present).
   Otherwise return the type and text of the message. Takes an
   optional max amount of time to wait, in ms, and whether to refresh
   the page periodically while waiting for a notification."
  [ & [{:keys [timeout-ms refresh?] :or {timeout-ms 2000}}]]
  (loop-with-timeout timeout-ms [error-notifs #{}]
    (let [new-notifs (set (notifications
                           {:timeout-ms (if refresh? 15000 timeout-ms)}))
          error-notifs (union error-notifs (filter #(= (:type %) :error) new-notifs))] 
      (if (and (not-empty new-notifs) (empty? error-notifs))
        new-notifs 
        (do (when refresh?
              (browser refresh))
            (recur error-notifs))))
    (if (empty? error-notifs) 
      (throw+ {:type ::no-success-message-error}
              "Expected a success notification, but none appeared within the timeout period.")
      (throw+ {:types (matching-errors error-notifs) :notifications error-notifs}))))

(defn check-for-error
  "Waits for a notification up to the optional timeout (in ms), throws
  an exception if error notification appears."
  [ & [{:keys [timeout-ms] :as m}]]
  (try+ (check-for-success m)
        (catch [:type ::no-success-message-error] _)))

(defn verify-success
  "Calls task-fn and checks for a success message afterwards. If none
   is found, or error notifications appear, throws an exception."
  [task-fn]
  (let [notifications (task-fn)]
    (verify-that (every? success? notifications))))

(def ^{:doc "Navigates to a named location in the UI. The first
  argument should be a keyword for the place in the page tree to
  navigate to. The 2nd optional argument is a mapping of keywords to
  strings, if any arguments are needed to navigate there.
  Example: (navigate :named-organization-page {:org-name 'My org'})
  See also katello.locators/page-tree for all the places that can be
  navigated to."
       :arglists '([location-kw & [argmap]])}
  navigate (nav/nav-fn #'locators/page-tree))

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

(defn create-changeset
  "Creates a changeset for promotion from env-name to next-env name."
  [env-name next-env-name changeset-name]
  (navigate :named-environment-promotions-page {:env-name env-name
                                                :next-env-name next-env-name})
  (->browser (click :new-promotion-changeset)
             (setText :changeset-name-text changeset-name)
             (click :save-changeset))
  (check-for-success))

(defn add-to-changeset
  "Adds the given content to an existing changeset. The originating
   and target environments need to be specified to find to locate the
   changeset."
  [changeset-name from-env to-env content]
  (navigate :named-changeset-promotions-page {:env-name from-env
                                              :next-env-name to-env
                                              :changeset-name changeset-name})
  (doseq [category (keys content)]
    (browser click (-> category name (str "-category") keyword))
    (doseq [item (content category)]
      (browser click (locators/promotion-add-content-item item)))
    (browser sleep 5000)))  ;;sleep to wait for browser->server comms to update changeset
;;can't navigate away until that's done

(defn promote-changeset
  "Promotes the given changeset to its target environment. An optional
   timeout-ms key will specify how long to wait for the promotion to
   complete successfully."
  [changeset-name {:keys [from-env to-env timeout-ms]}]
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
      (check-for-success {:timeout-ms 180000 :refresh? true}))))

(defn promote-content
  "Promotes the given content from one environment to another. Example
  content:
     {:products ['Product1' 'Product2']} "
  [from-env to-env content]
  (let [changeset (uniqueify "changeset")]
    (create-changeset from-env to-env changeset)
    (add-to-changeset changeset from-env to-env content)
    (promote-changeset changeset {:from-env from-env
                                  :to-env to-env
                                  :timeout-ms 300000})))

(defn extract-left-pane-list []
  "Extract data from the left pane, accepts locator as argument
   for example, extract-left-pane-list locators/left-pane-field-list"
  (let [elems (for [index (iterate inc 1)]
                (locators/left-pane-field-list (str index)))]
    (doall (take-while identity (for [elem elems]
                                  (try (browser getText elem)
                                       (catch SeleniumException e nil)))))))


(defn- extract-content []
  (let [elems (for [index (iterate inc 1)]
                (locators/promotion-content-item-n (str index)))
        retrieve (fn [elem]
                   (try (browser getText elem)
                        (catch Exception e nil)))]
    (->> (map retrieve elems) (take-while identity) set)))

(defn environment-content
  "Returns the content that is available to promote, in the given environment."
  [env-name]
  (navigate :named-environment-promotions-page {:env-name env-name
                                                :next-env-name nil})
  (let [categories [:products :templates]]
    (zipmap categories
            (doall (for [category categories]
                     (do
                       (browser click (-> category name (str "-category") keyword))
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

(defn create-organization
  "Creates an organization with the given name and optional description."
  [name & [{:keys [description initial-env-name initial-env-description go-through-org-switcher]}]]
  (navigate (if go-through-org-switcher :new-organization-page-via-org-switcher :new-organization-page))
  (fill-ajax-form {:org-name-text name
                   :org-description-text description
                   :org-initial-env-name-text initial-env-name
                   :org-initial-env-desc-text initial-env-description}
                  :create-organization)
  (check-for-success))

(defn delete-organization
  "Deletes the named organization."
  [org-name]
  (navigate :named-organization-page {:org-name org-name})
  (browser click :remove-organization)
  (browser click :confirmation-yes)
  (check-for-success) ;queueing success
  (wait-for-notification-gone)
  (check-for-success {:timeout-ms 180000 :refresh? true})) ;for actual delete

(defn create-environment
  "Creates an environment with the given name, and a map containing
   the organization name to create the environment in, the prior
   environment, and an optional description."
  [name {:keys [org-name description prior-env]}]
  (navigate :new-environment-page {:org-name org-name})
  (fill-ajax-form {:env-name-text name
                   :env-description-text description
                   :prior-environment prior-env}
                  :create-environment)
  (check-for-success))

(defn delete-environment
  "Deletes an environment from the given organization."
  [env-name {:keys [org-name]}]
  (navigate :named-environment-page {:org-name org-name
                                     :env-name env-name})
  (if (browser isElementPresent :remove-environment)
    (browser click :remove-environment)
    (throw+ {:type :env-cant-be-deleted :env-name env-name}))
  (browser click :confirmation-yes)
  (check-for-success))

(defn edit-organization
  "Edits an organization. Currently the only property of an org that
   can be edited is the org's description."
  [org-name & {:keys [description]}]
  (navigate :named-organization-page {:org-name org-name})
  (in-place-edit {:org-description-text description})
  (check-for-success))

(defn edit-environment
  "Edits an environment with the given name. Also takes a map
   containing the name of the environment's organization, and optional
   fields: a new description."
  [env-name {:keys [org-name description]}]
  (navigate :named-environment-page {:org-name org-name
                                     :env-name env-name})
  (in-place-edit {:env-description-text description})
  (check-for-success))

(defn create-environment-path
  "Creates a path of environments in the given org. All the names in
  the environment list must not already exist in the given org. Example:
  (create-environment-path 'ACME_Corporation' ['Dev' 'QA' 'Production'])"
  [org-name environments]
  (let [env-chain  (partition 2 1 (concat [library] environments))]
    (doseq [[prior curr] env-chain]
      (create-environment curr {:prior-env prior
                                :org-name org-name}))))

(defn create-provider
  "Creates a custom provider with the given name and description."
  [{:keys [name description]}]
  (navigate :new-provider-page)
  (fill-ajax-form {:provider-name-text name
                   :provider-description-text description}
                  :provider-create-save)
  (check-for-success))

(defn add-product
  "Adds a product to a provider, with the given name and description."
  [{:keys [provider-name name description]}]
  (navigate :provider-products-repos-page {:provider-name provider-name})
  (browser click :add-product)
  (fill-ajax-form {:product-name-text name
                   :product-description-text description}
                  :create-product)
  (check-for-success))

(defn delete-product
  "Deletes a product from the given provider."
  [{:keys [name provider-name]}]
  (navigate :named-product-page {:provider-name provider-name
                                 :product-name name})
  (browser click :remove-product)
  (browser click :confirmation-yes)
  (check-for-success))

(defn add-repo
  "Adds a repository under the given provider and product. Requires a
   name and url be given for the repo."
  [{:keys [provider-name product-name name url]}]
  (navigate :provider-products-repos-page {:provider-name provider-name})
  (browser click (locators/add-repository product-name))
  (fill-ajax-form {:repo-name-text name
                   :repo-url-text url}
                  :save-repository)
  (check-for-success))

(defn delete-repo
  "Deletes a repository from the given provider and product."
  [{:keys [name provider-name product-name]}]
  (navigate :named-repo-page {:provider-name provider-name
                              :product-name product-name
                              :repo-name name})
  (browser click :remove-repository)
  (browser click :confirmation-yes)
  (check-for-success))

(defn delete-provider
  "Deletes the named custom provider."
  [name]
  (navigate :named-provider-page {:provider-name name})
  (browser click :remove-provider)
  (browser click :confirmation-yes)
  (check-for-success))

(defn edit-provider
  "Edits the named custom provider. Takes an optional new name, and
  new description." [{:keys [name new-name description]}]
  (navigate :provider-details-page {:provider-name name})
  (in-place-edit {:provider-name-text new-name
                  :provider-description-text description})
  (check-for-success))

(defn logged-in?
  "Returns true if the browser is currently showing a page where a
  user is logged in."
  []
  (browser isElementPresent :log-out))

(defn logged-out?
  "Returns true if the login page is displayed."
  []
  (browser isElementPresent :log-in))

(defn logout
  "Logs out the current user from the UI."
  []
  (when-not (logged-out?)
    (browser clickAndWait :log-out)))

(defn switch-org "Switch to the given organization in the UI."
  [org-name]
  (browser click :org-switcher)
  (browser clickAndWait (locators/org-switcher org-name)))

(defn ensure-org "Switch to the given org if the UI shows we are not already there."
  [org-name]
  (when-not (-> (browser getText :org-switcher) (= org-name))
    (switch-org org-name)))

(defn login
  "Logs in a user to the UI with the given username and password. If
   any user is currently logged in, he will be logged out first."
  [username password & [org]]
  (when (logged-in?) (logout))
  (fill-ajax-form {:username-text username
                   :password-text password}
                  :log-in)
  (let [retVal (check-for-success)]
    (switch-org (or org (@config :admin-org)))
    retVal))

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

(defn create-user
  "Creates a user with the given name and properties."
  [username {:keys [password password-confirm email default-org default-env]}]
  (navigate :users-page)
  (browser click :new-user)
  (let [env-chooser (fn [env] (when env
                               (locators/select-environment-widget env)))]
    (fill-ajax-form [:user-username-text username
                     :user-password-text password
                     :user-confirm-text (or password-confirm password)
                     :user-email-text email
                     :user-default-org default-org
                     env-chooser [default-env]]
                    :save-user))
  (check-for-success))

(defn delete-user "Deletes the given user."
  [username]
  (navigate :named-user-page {:username username})
  (browser click :remove-user)
  (browser click :confirmation-yes)
  (check-for-success))
  
(defn edit-user
  "Edits the given user, changing any of the given properties (can
  change more than one at once)."
  [username {:keys [inline-help clear-disabled-helptips
                    new-password new-password-confirm new-email]}]
  (navigate :named-user-page {:username username})
  (when new-password
    (browser setText :user-password-text new-password)
    (browser setText :user-confirm-text (or new-password-confirm new-password))

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

(defn clear-search []
  (->browser (click :search-menu)
             (click :search-clear-the-search)))

(defn search
  "Search for criteria in entity-type, scope not yet implemented.
  if with-favorite is specified, criteria is ignored and the existing
  search favorite is chosen from the search menu. If add-as-favorite
  is true, use criteria and save it as a favorite, and also execute
  the search."
  [entity-type & [{:keys [criteria scope with-favorite add-as-favorite]}]]
  (navigate (entity-type {:users :users-page 
                          :organizations :manage-organizations-page
                          :roles :roles-page
                          :subscriptions :redhat-subscriptions-page
                          :gpg-keys :gpg-keys-page
                          :sync-plans :sync-plans-page
                          :systems  :systems-all-page
                          :system-groups :system-groups-page
                          :activation-keys :activation-keys-page
                          :changeset-promotion-history :changeset-promotion-history-page}))
  (if with-favorite
    (->browser (click :search-menu)
               (click (locators/search-favorite with-favorite)))
    (do (browser type :search-bar criteria)
        (when add-as-favorite
          (->browser (click :search-menu)
                     (click :search-save-as-favorite)))
        (browser click :search-submit)))
  (check-for-error {:timeout-ms 2000}))

(defn create-role
  "Creates a role with the given name and optional description."
  [name & [{:keys [description]}]]
  (navigate :roles-page)
  (browser click :new-role)
  (fill-ajax-form {:new-role-name-text name
                   :new-role-description-text description}
                  :save-role)
  (check-for-success))

(defn assign-role
  "Assigns the given user to the given roles. Roles should be a list
  of roles to assign."
  [{:keys [user roles]}]
  (navigate :user-roles-permissions-page {:username user})
  (doseq [role roles]
    (browser click (locators/plus-icon role)))
  (browser click :save-roles)
  (check-for-success))

(defn edit-role
  "Edits a role to add new permissions, remove existing permissions,
  and assign users to the role. Example:

  (edit-role 'myrole'
             {:add-permissions [{:resource-type 'Organizations'
                                 :verbs ['Read Organization']
                                 :name 'newPerm1'}]
              :remove-permissions ['existingPerm1' 'existingPerm2']
              :users ['joe' 'bob']})"
  [name {:keys [add-permissions remove-permissions users]}]
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

(defn remove-role
  "Deletes the given role."
  [name]
  (navigate :named-role-page {:role-name name})
  (browser click :remove-role)
  (browser click :confirmation-yes)
  (check-for-success))

(def sync-messages {:ok "Sync complete."
                    :fail "Error syncing!"})

(defn sync-complete-status
  "Returns final status if complete. If sync is still in progress, not
  synced, or queued, returns nil."
  [product]
  (some #{(browser getText (locators/provider-sync-progress product))}
        (vals sync-messages)))

(defn sync-success? "Returns true if given sync result is a success."
  [res]
  (= res (:ok sync-messages)))


(defn sync-repos
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
                       (or (sync-complete-status repo)
                           (do (Thread/sleep 10000)
                               (recur)))))))

(defn edit-system
  "Edits the properties of the given system. Optionally specify a new
  name, a new description, and a new location."
  [name {:keys [new-name description location release-version]}]
  (navigate :named-systems-page {:system-name name})
  (in-place-edit {:system-name-text-edit new-name
                  :system-description-text-edit description
                  :system-location-text-edit location
                  :system-release-version-select release-version})
  (check-for-success))

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

(def syncplan-dateformat (SimpleDateFormat. "MM/dd/yyyy"))
(def syncplan-timeformat (SimpleDateFormat. "hh:mm aa"))
(defn date-str [d] (.format syncplan-dateformat d))
(defn time-str [d] (.format syncplan-timeformat d))

(defn- split-date [{:keys [start-date start-date-literal start-time-literal]}]
  (list (if start-date (date-str start-date) start-date-literal)
        (if start-date (time-str start-date) start-time-literal)))

(defn create-sync-plan
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

(defn edit-sync-plan
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
                    :sync-plan-date-text date}))
  (check-for-success))

(defn sync-schedule
  "Schedules the given list of products to be synced using the given
  sync plan name."
  [{:keys [products plan-name]}]
  (navigate :sync-schedule-page)
  (doseq [product products]
    (browser click (locators/schedule product)))
  (browser click (locators/sync-plan plan-name))
  (browser clickAndWait :apply-sync-schedule )
  (check-for-success))

(defn current-sync-plan
  "Returns a map of what sync plan a product is currently scheduled
  for. nil if UI says 'None'"
  [product-names]
  (navigate :sync-schedule-page)
  (zipmap product-names
          (replace {"None" nil}
                   (doall (for [product-name product-names]
                            (browser getText (locators/product-schedule product-name)))))))

(defn create-activation-key
  "Creates an activation key with the given properties. Description
  and system-template are optional."
  [{:keys [name description environment system-template] :as m}]
  (navigate :new-activation-key-page)
  (browser click (locators/environment-link environment))
  (fill-ajax-form {:activation-key-name-text name
                   :activation-key-description-text description
                   :activation-key-template-select system-template}
                  :save-activation-key)
  (check-for-success))

(defn delete-activation-key
  "Deletes the given activation key."
  [name]
  (navigate :named-activation-key-page {:activation-key-name name})
  (browser click :remove-activation-key)
  (browser click :confirmation-yes)
  (check-for-success))

(defn upload-subscription-manifest
  "Uploads a subscription manifest from the filesystem local to the
   selenium browser. Optionally specify a new repository url for Red
   Hat content- if not specified, the default url is kept. Optionally
   specify whether to force the upload."
  [file-path & [{:keys [repository-url]}]]
  (navigate :redhat-subscriptions-page)
  (when-not (browser isElementPresent :choose-file)
    (browser click :import-manifest))
  (when repository-url
    (in-place-edit {:redhat-provider-repository-url-text repository-url})
    (check-for-success))
  (fill-ajax-form {:choose-file file-path}
                  :upload)
  (check-for-success {:timeout-ms 600000 :refresh? true})) ;using asynchronous notification until the bug https://bugzilla.redhat.com/show_bug.cgi?id=842325 gets fixed.
  ;(check-for-success))
  
(defn manifest-already-uploaded?
  "Returns true if the current organization already has Red Hat
  content uploaded."
  []
  (navigate :redhat-repositories-page)
  (browser isElementPresent :subscriptions-items))

(defn create-template
  "Creates a system template with the given name and optional
  description."
  [{:keys [name description]}]
  (navigate :new-system-template-page)
  (fill-ajax-form {:template-name-text name
                   :template-description-text description}
                  :save-new-template)
  (check-for-success))

(defn add-to-template
  "Adds content to a given template.  Example:
   (add-to-template 'mytemplate' [{:product 'prod3'
                                   :packages ['rpm1' 'rpm2']}
                                  {:product 'prod6'
                                   :repositories ['x86_64']}]"
  [template content]
  (navigate :named-system-template-page {:template-name template})
  (let [add-item (fn [item] (locators/toggle locators/template-toggler item true))]
    (doseq [group content]
      (let [category-keyword (-> group (dissoc :product) keys first)
            category-name (-> category-keyword
                             name
                             (.replace "-" " ")
                             capitalize-all)]
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

(defn enable-redhat-repositories
  "Enable the given list of repos in the current org."
  [repos]
  (navigate :redhat-repositories-page)
  (doseq [repo repos]
    (browser check (locators/repo-enable-checkbox repo))))

(defn current-org []
  "Return the currently active org (a string) shown in the org switcher."
  (browser getText :active-org))

(defmacro with-org
  "Switch to organization org-name, then execute the code in body. Finally,
   switch back to the previous org, even if there was an error."
   [org-name & body]
  `(let [curr-org# (current-org)]
     (try (switch-org ~org-name)
          ~@body
          (finally (switch-org curr-org#)))))

(defn assign-user-default-org-and-env 
  "Assigns a default organization and environment to a user"
  [username org-name env-name]
  (navigate :user-environments-page {:username username})
  (browser select :user-default-org-select org-name)
  (browser click (locators/environment-link env-name))
  (browser click :save-user-environment)
  (check-for-success))

(defn create-gpg-key [name & [{:keys [filename contents]}]]
  (assert (not (and filename contents))
          "Must specify one one of :filename or :contents.")
  (assert (string? name))
  (navigate :new-gpg-key-page)
  (if filename
    (fill-ajax-form {:gpg-key-name-text name
                     :gpg-key-file-upload-text filename}
                    :gpg-key-upload-button)
    (fill-ajax-form {:gpg-key-name-text name
                     :gpg-key-content-text contents}
                    :gpg-keys-save))
  (check-for-success))
 

(defn remove-gpg-key 
  "Deletes existing GPG keys"
  [gpg-key-name]
  (navigate :named-gpgkey-page {:gpg-key-name gpg-key-name})
  (browser click :remove-gpg-key )
  (browser click :confirmation-yes)
  (check-for-success))

(defn sync-and-promote [products from-env to-env]
  (let [all-prods (map :name products)
        all-repos (apply concat (map :repos products))
        sync-results (sync-repos all-repos {:timeout 600000})]
        (verify-that (every? (fn [[_ res]] (sync-success? res))
                             sync-results))
        (promote-content from-env to-env {:products all-prods})))

(defn create-package-filter [name & [{:keys [description]}]]
  "Creates new Package Filter"
  (assert (string? name))
  (navigate :new-package-filter-page)
    (fill-ajax-form {:new-package-filter-name  name
                     :new-package-filter-description description}
                     :save-new-package-filter)
  (check-for-success))

(defn remove-package-filter 
  "Deletes existing Package Filter"
  [package-filter-name]
  (navigate :named-package-filter-page {:package-filter-name package-filter-name})
  (browser click :remove-package-filter-key )
  (browser click :confirmation-yes)
  (check-for-success))

(defn create-system
  "Creates a system"
   [name & [{:keys [sockets system-arch]}]]
   (navigate :new-system-page)
   (fill-ajax-form {:system-name-text name
                    :system-sockets-text sockets
                    :system-arch-select (or system-arch "x86_64")}
                    :create-system)
   (check-for-success))

(defn create-system-group
  "Creates a system-groups"
   [name & [{:keys [description]}]]
   (navigate :new-system-groups-page)
   (fill-ajax-form {:system-group-name-text name
                    :system-group-description-text description}
                    :create-system-groups)
   (check-for-success))

(defn add-to-system-group
  "Adds a system to a System-Group"
   [system-group system-name]
   (navigate :named-system-groups-page {:system-group system-group})
   (fill-ajax-form {:system-groups-hostname-toadd system-name}
                    :system-groups-add-system))

