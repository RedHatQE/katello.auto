(ns katello.notifications
  (:require [katello.ui :as ui]
            [clojure.data.json :as json]
            [com.redhat.qe.auto.selenium.selenium 
             :refer [browser loop-with-timeout]] 
            [slingshot.slingshot :refer [throw+ try+]]
            [test.assert :as assert])
  (:refer-clojure :exclude [flush])
  (:import [com.thoughtworks.selenium SeleniumException]))

;;
;; Notifications
;;

(def notice-array-js-var "window.notices.noticeArray")

(def ^{:doc "All the different validation error messages that Katello
             can throw. The keys are keywords that can be used to
             refer to this type of error, and the values are regexes
             that match the error notification message in the UI."}
  validation-errors
  (let [errors {::name-taken-error                    #"(Username|Name) has already been taken"
                ::label-taken-error                   #"Label (already exists|has already been taken)"
                ::sg-name-taken-error                 #"Name must be unique within one organization"
                ::name-no-leading-trailing-whitespace #"(Username|Name) must not contain leading or trailing white space"
                ::name-must-not-contain-characters    #"Name cannot contain characters other than"
                ::name-must-not-contain-html          #"Username cannot contain characters >, <, or /"
                ::org-name-must-not-contain-html      #"Name cannot contain characters >, <, or /"
                ::env-name-lib-is-builtin             #"Name : 'Library' is a built-in environment"
                ::env-name-must-be-unique-within-org  #"Name of environment must be unique within one organization" 
                ::env-label-lib-is-builtin            #"Label : 'Library' is a built-in environment"
                ::env-label-must-be-unique-within-org #"Label of environment must be unique within one organization" 
                ::repository-url-invalid              #"Repository url is invalid"
                ::start-date-time-cant-be-blank       #"Date and Time can't be blank"
                ::password-too-short                  #"Password must be at least"
                ::username-must-contain-3-char        #"Username must contain at least 3 character"
                ::username-cant-be-blank              #"Username can't be blank"
                ::name-128-char-limit                 #"(Username|Name) cannot contain more than 128 characters"
                ::system-name-char-limit              #"Name is too long.*maximum is 250"
                ::sys-description-255-char-limit      #"Description cannot contain more than 255 characters"
                ::sys-location-255-char-limit         #"Location is too long \(maximum is 255 characters\)"
                ::repository-url-cant-be-blank        #"Repository url can't be blank"
                ::name-cant-be-blank                  #"Name can't be blank"
                ::max-systems-must-be-positive        #"System limit must be a positive"
                ::max-systems-may-not-be-zero         #"System limit may not be set to 0"
                ::name-too-long                       #"Name is too long"
                ::login-is-invalid                    #"Login is invalid"}]
    
    (doseq [e (keys errors)]
      (derive e ::validation-error))  ; validation-error is a parent type
                                      ; so you can catch that type to
                                      ; mean "any" validation error.
    errors))


(def reqtypes
  {:ak-create                "activation_keys___create"
   :ak-destroy               "activation_keys___destroy"
   :ak-add-subscriptions     "activation_keys___add_subscriptions" 
   :ak-remove-subscriptions  "activation_keys___remove_subscriptions"
   :ak-add-sysgrps           "activation_keys___add_system_groups"
   
   :cv-create                "content_view_definitions___create"
   :cv-destroy               "content_view_definitions___destroy"
   :cv-clone                 "content_view_definitions___clone"
   :cv-update-cv             "content_view_definitions___update_component_views"
   :cv-update-content        "content_view_definitions___update_content"
   :cv-update                "content_view_definitions___update"
   :cv-publish               "content_view_definitions___publish"
   
   :gpg-keys-create           "gpg_keys___create"
   :gpg-keys-destroy          "gpg_keys___destroy"
   
   :prov-create              "providers___create"
   :prov-destroy             "providers___destroy"
   :prov-update              "providers___update"
   
   :prod-create              "products___create"
   :prod-update              "products___update"
   :prod-destroy             "products___destroy"

   :distributor-create       "distributors___create"
   :distributor-destroy      "distributors___destroy"
   
   :repo-create              "repositories___create"
   :repo-destroy             "repositories___destroy"
   
   :repo-update-gpg-key      "repositories___update_gpg_key"
   
   :sys-create               "systems___create"
   :sys-destroy              "systems___destroy"
   :sys-bulk-destroy         "systems___bulk_destroy"
   :sys-update               "systems___update"
   :sys-update-subscriptions "systems___update_subscriptions"
   :sys-add-sysgrps          "systems___add_system_groups"
   :sys-add-bulk-sysgrps     "systems___bulk_add_system_group"
 
   :sysgrps-create           "system_groups___create"
   :sysgrps-copy             "system_groups___copy"
   :sysgrps-rm-sys           "system_groups___remove_systems"
   :sysgrps-add-sys          "system_groups___add_systems"
   :sysgrps-destroy-sys      "system_groups___destroy_systems" 
   :sysgrps-update           "system_groups___update"
   :sysgrps-destroy          "system_groups___destroy"
   
   :env-create               "environments___create"
   :env-destroy              "environments___destroy"
   
   :org-create               "organizations___create"
   :org-destroy              "organizations___destroy"
   
   :roles-create             "roles___create"
   :roles-destroy            "roles___destroy"
   :roles-update             "roles___update"
   :roles-create-permission  "roles___create_permission"
   :roles-destroy-permission "roles___destroy_permission"
   
   :users-create             "users___create"
   :users-destroy            "users___destroy"
   :users-update             "users___update"
   :users-update-roles       "users___update_roles"
   :users-update-env         "users___update_environment"

   :sync-create              "sync_plans___create"
   :sync-destroy             "sync_plans___destroy"
   :sync-update              "sync_plans___update"
   
   :changeset-create         "changesets___create"
   :changeset-apply          "changesets___apply"
   :changeset-promoted       "changesets___promote"})


(def ^{:doc "A mapping of known errors in Katello. This helps
  automation throw and catch the right type of exception interally,
  taking UI error messages and mapping them to internal error types."}
  known-errors
  (let [errors {::invalid-credentials                   #"Authentication failed"
                ::promotion-already-in-progress         #"Cannot promote.*while another changeset"
                ::import-older-than-existing-data       #"Import is older than existing data"
                ::import-same-as-existing-data          #"Import is the same as existing data"
                ::systems-exceeds-group-limit           #"System limit may not be less than the number of systems associated with the system group"
                ::bulk-systems-exceeds-group-limit      #"System Group maximum number of systems exceeded.*for more details."
                ::add-systems-greater-than-allowed      #"You cannot have more.*associated with system group.*"
                ::distributor-has-already-been-imported #"This distributor has already been imported by another owner"
                ::deletion-already-in-progress          #"Cannot delete.*while another changeset"}]
    (doseq [e (conj (keys errors) ::validation-error)]
      (derive e ::katello-error))
    (merge errors validation-errors)))

(defn matching-errors
  "Returns a set of matching known errors"
  [notifs]
  (->> known-errors
       (filter (fn [[_ v]] (some not-empty (for [msg (concat (mapcat :notices notifs)
                                                             (mapcat :validationErrors notifs))]
                                             (re-find v msg)))))
     (map key)
     set))

(def success?
  "Returns a function that returns true if the given notification is a 'success'
   type notification (aka green notification in the UI)."
  ^{:type :serializable.fn/serializable-fn
    :serializable.fn/source 'success?}
  (fn [notif]
    (and notif (-> notif :level (= :success)))))

(def error?
  "Returns a function that returns true if the given notification is an 'error'
   type notification (aka gray notification in the UI)."
  ^{:type :serializable.fn/serializable-fn
    :serializable.fn/source 'error?}
  (fn [notif]
    (and notif (-> notif :level (= :error)))))

(defn request-type? [req-type]
  "Returns a function that returns true if the given notification contains the
   specified request type."
  {:pre (some #{req-type} (keys reqtypes))}
  (fn [notif]
    (= (req-type reqtypes) (:requestType notif))))

(defn flush []
  "Clears the javascript notice array."
  (browser runScript "window.notices.noticeArray = []"))

(defn notifications
  "Gets all notifications from the page, returns a list of maps
   representing the notifications. Waits for timeout-ms for at least
   one notification to appear. Does not do any extra waiting after the
   first notification is detected. Default timeout is 15 seconds."
  []
  (try (let [notices (->> notice-array-js-var
                          (format "JSON.stringify(%s)") 
                          (browser getEval)
                          json/read-json)]
         (for [notice notices] 
           (ui/map->Notification (assoc notice :level (keyword (:level notice))))))
       (catch SeleniumException e '())))


(defn check-for-success
  "Returns notification information from the UI. Will wait up to
   timeout-ms (defaults to 2000) for any notificaiton to appear. Takes
   an optional predicate to filter notifications (any filtered
   notifications are ignored as if they didn't exist). If no
   notifications are collected, an exception is thrown. If
   notifications are collected and none are errors, they are returned.
   If any are errors, an exception is thrown containing all
   notifications."
  [ & [{:keys [timeout-ms match-pred]
        :or {timeout-ms 2000, match-pred (constantly true)}}]]
  (loop-with-timeout timeout-ms []
    (let [notifs (->> (notifications)
                      set
                      (filter match-pred))]
      (cond (empty? notifs)  (do (Thread/sleep 2000) (recur))
            (some error? notifs) (throw+ notifs)
            :else notifs))
    (throw+ {:type ::no-success-message-error} 
            "Expected a success notification, but none appeared within the timeout period.")))


(defn verify-no-error
  "Waits for a notification up to the optional timeout (in ms), throws
  an exception if error notification appears."
  [ & [{:keys [timeout-ms match-pred] :as m}]]
  (try+ (check-for-success m)
        (catch [:type ::no-success-message-error] _)))



