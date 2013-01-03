(ns katello.notifications
  (:require [katello.locators :as locators]
            [com.redhat.qe.auto.selenium.selenium 
              :refer [browser loop-with-timeout]] 
            [slingshot.slingshot :refer [throw+ try+]]
            [tools.verify :refer [verify-that]]
            [clojure.set :refer [union]])
  (:refer-clojure :exclude [flush])
  (:import [com.thoughtworks.selenium SeleniumException]))

;;
;; Notifications
;;

(def ^{:doc "All the different validation error messages that Katello
             can throw. The keys are keywords that can be used to
             refer to this type of error, and the values are regexes
             that match the error notification message in the UI."}
  validation-errors
  (let [errors {::name-taken-error                    #"(Username|Name) has already been taken"
                ::sg-name-taken-error                 #"Name must be unique within one organization"
                ::name-no-leading-trailing-whitespace #"Name must not contain leading or trailing white space"
                ::name-must-not-contain-characters    #"Name cannot contain characters other than"
                ::name-must-be-unique-within-org      #"Name of environment must be unique within one organization" 
                ::repository-url-invalid              #"Repository url is invalid"
                ::start-date-time-cant-be-blank       #"Date and Time can't be blank"
                ::password-too-short                  #"Password must be at least"
                ::password-same-as-username           #"(Password|Username)" ;TODO after bug (open-bz-bugs "841499") is fixed add real notification
                ::repository-url-cant-be-blank        #"Repository url can't be blank"
                ::name-cant-be-blank                  #"Name can't be blank"
                ::max-systems-must-be-positive        #"Max systems must be a positive"
                ::max-systems-may-not-be-zero         #"Max systems may not be set to 0"}]
    
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
                ::import-same-as-existing-data          #"Import is the same as existing data"
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
  "Returns a function that returns true if the given notification is a 'success'
   type notification (aka green notification in the UI)."
  ^{:type :serializable.fn/serializable-fn
    :serializable.fn/source 'success?}
  (fn [notif]
    (and notif (-> notif :type (= :success)))))

(def error?
  "Returns a function that returns true if the given notification is an 'error'
   type notification (aka gray notification in the UI)."
  ^{:type :serializable.fn/serializable-fn
    :serializable.fn/source 'error?}
  (fn [notif]
    (and notif (-> notif :type (= :error)))))

(defn request-type? [req-type]
  "Returns a function that returns true if the given notification contains the
   specified request type."
  (fn [notif]
    (= req-type (:requestType notif))))

(defn flush []
  "Clears the javascript notice array."
  (browser runScript "window.notices.noticeArray = []"))

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
    (when-not (empty? error-notifs) 
      (throw+ {:types (matching-errors error-notifs) :notifications error-notifs}))))


(defn verify-no-error
  "Waits for a notification up to the optional timeout (in ms), throws
  an exception if error notification appears."
  [ & [{:keys [timeout-ms match-pred] 
        :or {match-pred (constantly true)}
        :as m}]]
  (try+ (check-for-success m)
        (catch [:type ::no-success-message-error] _)))

(defn verify-success
  "Calls task-fn and checks for a success message afterwards. If none
   is found, or error notifications appear, throws an exception."
  [task-fn]
  (let [notifications (task-fn)]
    (verify-that (every? success? notifications))))

