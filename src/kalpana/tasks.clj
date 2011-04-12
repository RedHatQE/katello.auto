(ns  kalpana.tasks
  (:require [kalpana.locators :as locators]
            [com.redhat.qe.auto.navigate :as nav]
            [clojure.contrib.logging :as log]
            [clojure.string :as string])
  (:use [com.redhat.qe.auto.selenium.selenium :only [connect browser ->browser fill-form]]
        [com.redhat.qe.config :only [same-name]]
        [error.handler :only [raise]]
        [com.redhat.qe.verify :only [verify]])
  (:import [com.thoughtworks.selenium SeleniumException]))
;;tasks
(defn timestamp
  "Returns a string with timestamp (time in millis since
1970) appended onto the end.  If optional n is specified, returns a
list of n timestamped strings, where the millis is incremented by one
for each item."
  ([s] (str s "-" (System/currentTimeMillis)))
  ([s n] (take n (map #(str s "-" %) (iterate inc (System/currentTimeMillis))))))

(defn cant-be-blank-errors
  "Takes collection of keywords like :name and produces map entry like
:name-cant-be-blank #\"Name can't be blank"
  [coll]
  (same-name identity
             (comp re-pattern
                   string/capitalize
                   #(string/replace % " cant " " can't "))
             (map #(-> (name %) (str "-cant-be-blank") keyword)
                  coll)))

(def known-errors
  (merge {:name-taken-error #"Name has already been taken"}
         (cant-be-blank-errors [:name
                                :certificate
                                :login-credential.username
                                :repository-url
                                :login-credential.password])))

(defn matching-error
  "Returns a keyword of known error, if the message matches any of
  them.  If no matches, returns :kalpana-error."
  [message]
  (or (some #(let [re (known-errors %)]
               (if (re-find re message) % false) )
            (keys known-errors))
      :kalpana-error))

(defn check-for-error
  "Checks the page for an error message, if present raises an
  exception with the contents of the error message."
  []
  (try (browser waitForElement :error-message "5000")
       (let [message (browser getText :error-message)]
         (raise {:type (matching-error message) :msg message}))
       (catch SeleniumException e nil)))

(defn success-message
  "If a success message is present on the current page, returns it,
otherwise nil."
  []
  (try (browser waitForElement :success-message "5000")
       (browser getText :success-message)   
       (catch SeleniumException e nil)))

(defn check-for-success []
  (check-for-error)
  (or (success-message)
      (raise {:type :no-success-message-error
              :msg "Expected a result message, but none is present on page."})))

(defn verify-success [task-fn]
  (let [resulting-message (task-fn)]
    (verify (string? resulting-message))))

(def navigate (nav/nav-fn locators/page-tree))

(defn promote-content [from-env content]
  (navigate :named-environment-promotions-page {:env-name from-env})
  (doseq [category (keys content)]
    (browser click (-> category name (str "-category") keyword))
    (doseq [item (content category)]
      (browser waitAndClick (locators/promotion-add-content-item item) "10000")
      (comment "for some reason, this resets the previous selection! doh"
               (browser waitForElement (locators/promotion-remove-content-item item) "10000"))
      (browser sleep 5000))
    (browser clickAndWait :promote-to-next-environment)))

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
  (fill-form {:org-name-text name
              :org-description-text description}
             :create-organization)
  (check-for-success))

(defn delete-organization [org-name]
  (navigate :named-organization-page {:org-name org-name})
  (browser answerOnNextPrompt "OK")
  (browser clickAndWait :delete-organization)
  (check-for-success))

(defn create-environment
  [org name description & {:keys [prior-env] :or {prior-env nil}}]
  (navigate :new-environment-page {:org-name org})
  (let [items {:env-name-text name
               :env-description-text description}]
    (fill-form (if prior-env (merge items {:prior-environment prior-env})
                   items)
               :create-environment))
  (check-for-success))

(defn delete-environment [org-name env-name]
  (navigate :named-environment-page {:org-name org-name
                                     :env-name env-name})
  (browser clickAndWait :delete-environment)
  (check-for-success))

(defn create-content-provider [name description repo-url type username password]
  (navigate :new-content-provider-page)
  (fill-form  {:cp-name-text name
               :cp-description-text description
               :cp-repository-url-text repo-url
               :cp-type-list  type
               :cp-username-text username
               :cp-password-text password}
              :cp-create-save)
  (check-for-success))

(defn delete-content-provider [name]
  (navigate :named-content-provider-page {:cp-name name})
  (browser answerOnNextPrompt "OK")
  (browser clickAndWait :remove-content-provider)
  (check-for-success))

(defn edit-content-provider [name & {:keys [description repo-url type username password] :as changes}]
  (let [m {:description :cp-description-text
           :repo-url :cp-repository-url-text
           :type :cp-type-list
           :username :cp-username-text
           :password :cp-password-text}]
    (fill-form (zipmap (vals (select-keys m (keys changes)))
                       (vals changes))
               :cp-create-save)
    (check-for-success)))

(defn upload-subscription-manifest [cp-name filepath]
  (navigate :named-content-provider-page {:cp-name cp-name})
  (->browser (click :subscriptions)
             (setText :choose-file filepath)
             (clickAndWait :upload))
  (check-for-success))

(defn logout []
  (if (browser isElementPresent :log-in) (log/info "Already logged out.")
      (do (browser clickAndWait :log-out)
          (check-for-success))))

(defn login [username password]
  (if (browser isElementPresent :log-out)
    (do (log/warn "Already logged in, logging out.")
        (logout)))
  (do (fill-form {:username-text username
                  :password-text password}
                 :log-in)
      (check-for-success)))

(defn create-user [username password]
  (navigate :users-tab)
  (->browser (click :new-user)
             (waitForElement :new-user-username-text "7500")
             (comment "this can go back in after that annoying popup is gone"
                      "remember to split ->browser sexp"
                      (fill-form {:new-user-username-text username
                                  :new-user-password-text password
                                  :new-user-confirm-text password}
                                 :save-user))
             (setText :new-user-username-text username)
             (setText :new-user-password-text password)
             (setText :new-user-confirm-text password)
             (answerOnNextPrompt "OK")
             (clickAndWait :save-user)))

(defn create-role [name]
  (navigate :roles-tab)
  (->browser (click :new-role)
             (waitForElement :new-role-name-text "7500")
             (setText :new-role-name-text name)
             (answerOnNextPrompt "OK")
             (click :save-role)))
