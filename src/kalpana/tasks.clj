(ns kalpana.tasks
  (:require [kalpana.locators :as locators]
            [com.redhat.qe.auto.navigate :as nav]
            [clojure.contrib.logging :as log]
            [clojure.string :as string])
  (:use [com.redhat.qe.auto.selenium.selenium :only [connect browser ->browser
                                                     fill-form first-present
                                                     loop-with-timeout]]
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

(defn notification []
  (try (browser waitForElement :notification "5000")
       (let [msg (browser getText :notification)
             classattr ((into {} (browser getAttributes :notification)) "class")
             type ({"jnotify-notification-error" :error
                    "jnotify-notification-message" :message}
                   (-> (string/split classattr #" ") second))]
         {:type type :msg msg})
       (catch SeleniumException e nil)))

(defn check-for-success []
  (let [notif (notification)
        msg (:msg notif)]
    (cond (not notif) (raise {:type :no-success-message-error
                           :msg "Expected a result message, but none is present on page."})
          (= :error (notif :type)) (raise {:type (matching-error msg) :msg msg})
          :else msg)))

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
      (comment "for some reason, this resets the previous selection! doh")
      (browser waitForElement (locators/promotion-remove-content-item item) "10000")
      (comment (browser sleep 5000)))
    (browser clickAndWait :promote-to-next-environment)))

(defn extract-content [id]
  (let [elems (for [index (iterate inc 1)]
                (locators/promotion-content-item-n (str index)))
        retrieve (fn [elem]
                   (try (-> (browser getText elem) .trim (string/replace #" \w+$" ""))
                        (catch Exception e nil)))]
    (->> (map retrieve elems) (take-while identity) set))) 

(defn environment-content [env]
  (navigate :named-environment-promotions-page {:env-name env})
  (let [categories {:products "data-products_id"
                    :errata "data-errata_id"
                    :packages "data-packages_id"
                    :kickstart-trees "data-trees_id"}]
    (into {}
          (for [[category id] categories]
            (do (browser click (-> category name (str "-category") keyword))
                (first-present 20000
                               :promotion-empty-list
                               (locators/promotion-content-item-n (str 1)))
                [category (extract-content id)])))))

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

(defn create-content-provider [name description repo-url type & [username password]]
  (navigate :new-content-provider-page)
  (let [required-items {:cp-name-text name
                        :cp-description-text description
                        :cp-repository-url-text repo-url
                        :cp-type-list type}]
    (fill-form (merge required-items
                      (if username {:cp-username-text username
                                    :cp-password-text password}
                          {}))  
               :cp-create-save))
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
      (browser clickAndWait :log-out)))

(defn login [username password]
  (if (browser isElementPresent :log-out)
    (do (log/warn "Already logged in, logging out.")
        (logout)))
  (do (fill-form {:username-text username
                  :password-text password}
                 :log-in)
      (check-for-success)))

(defn create-user [username password]
  (comment "this can go back in after that annoying popup is gone"
           "remember to split ->browser sexp"
           (fill-form {:new-user-username-text username
                       :new-user-password-text password
                       :new-user-confirm-text password}
                      :save-user)) 
  (navigate :users-tab)
  (->browser
   (click :new-user)
   (waitForElement :new-user-username-text "7500")
   (setText :new-user-username-text username)
   (setText :new-user-password-text password)
   (setText :new-user-confirm-text password)
   (click :save-user)
   (sleep 5000))
  (check-for-success))

(defn create-role [name]
  (navigate :roles-tab)
  (->browser (click :new-role)
             (waitForElement :new-role-name-text "7500")
             (setText :new-role-name-text name)
             (answerOnNextPrompt "OK")
             (click :save-role)))

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
