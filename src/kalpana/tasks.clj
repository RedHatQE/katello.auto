(ns kalpana.tasks
  (:require [kalpana.locators :as locators]
            [com.redhat.qe.auto.navigate :as nav]
            [clojure.contrib.logging :as log]
            [clojure.string :as string])
  (:use [com.redhat.qe.auto.selenium.selenium :only [connect browser ->browser
                                                     fill-form first-present
                                                     loop-with-timeout]]
        
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

(def known-errors
   {:validation-failed #"Validation failed"})

(defn matching-error
  "Returns a keyword of known error, if the message matches any of
  them.  If no matches, returns :kalpana-error."
  [message]
  (or (some #(let [re (known-errors %)]
               (if (re-find re message) % false) )
            (keys known-errors))
      :kalpana-error))

(defn clear-all-notifications []
  (let [n (count
           (take-while (fn [index] (let [loc (locators/notification-close-index (str index))]
                                    (if (browser isElementPresent loc)
                                      (do (browser click loc) true))))
                       (iterate inc 1)))]
    (if (> n 0) (log/info (str "Cleared " n " notifications.")))
    n))

(defn notification "Gets the notification from the page, returns a map
object representing the notification (or nil if no notification is
present within a built-in timeout period)."
    []
    (try (browser waitForElement :notification "10000")
         (let [msg (browser getText :notification)
               classattr ((into {} (browser getAttributes :notification)) "class")
               type ({"jnotify-notification-error" :error
                      "jnotify-notification-message" :message
                      "jnotify-notification-success" :success}
                     (-> (string/split classattr #" ") second))]
           (log/info (str "Received " (name type) " notification \"" msg "\""))
           (clear-all-notifications)
           {:type type :msg msg})
         (catch SeleniumException e nil))) 

(defn check-for-success "Gets any notification from the UI, if there
is none or not a success notification, raise an exception.  Otherwise
return the text of the message."
  []
  (let [notif (notification)
        msg (:msg notif)]
    (cond (not notif) (raise
                       {:type :no-success-message-error
                        :msg "Expected a result message, but none is present on page."})
          (not= :success (notif :type)) (let [errtype (matching-error msg)]
                                          (log/debug (str "Raising error type " errtype ))
                                          (raise {:type errtype :msg msg}))
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

(defn ^{:TODO "finish me"} change-set-content [env]
  (navigate :named-environment-promotions-page {:env-name env})
  )

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
             :create-organization #(browser sleep 1000))
  (check-for-success))

(defn delete-organization [org-name]
  (navigate :named-organization-page {:org-name org-name})
  (browser answerOnNextPrompt "OK")
  (browser click :remove-organization)
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

(defn create-provider [name description type & [repo-url]]
  (let [types {:redhat "Red Hat" :custom "Custom"}]
    (assert (some #{type} (keys types)))
    (navigate :new-provider-page)
    (fill-form {:cp-name-text name
                :cp-description-text description
                :cp-repository-url-text (if (= type :redhat)
                                          repo-url nil)
                :cp-type-list (types type)}
               :cp-create-save #(browser sleep 1000))
    (check-for-success)))

(defn add-product [provider-name name description url & [yum? file?]]
  (navigate :provider-products-repos-page {:cp-name provider-name})
  
  ;;workaround need multiple clicks!
  (loop-with-timeout 10000 []
    (browser click :add-product)
    (if-not (browser isVisible :product-name-text)
      (do (browser sleep 1000)
          (recur))))

  (fill-form {:product-name-text name
              :product-description-text description
              :product-url-text url
              :product-yum-checkbox yum?
              :product-file-checkbox file?}
             :save-product #(browser sleep 3000))
  (check-for-success))

(defn delete-provider [name]
  (navigate :named-provider-page {:cp-name name})
  (browser answerOnNextPrompt "OK")
  (browser click :remove-provider)
  (check-for-success))

(defn edit-provider [name & {:keys [description repo-url type username password]}]
  (fill-form {:cp-description-text description
              :cp-repository-url-text repo-url
              :cp-type-list type
              :cp-username-text username
              :cp-password-text password} 
             :cp-create-save)
  (check-for-success))

(defn upload-subscription-manifest [cp-name filepath]
  (navigate :named-provider-page {:cp-name cp-name})
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

(defn create-user [username password & [password-confirm]] 
  (navigate :users-tab)
  (->browser (click :new-user)
             (waitForElement :new-user-username-text "7500"))
  (fill-form {:new-user-username-text username
              :new-user-password-text password
              :new-user-confirm-text (or password-confirm password)}
             :save-user #(browser sleep 1000))
  (check-for-success))

(defn delete-user [username]
  (navigate :named-user-page {:username username})
  (browser click :remove-user)
  (check-for-success))

(defn edit-user [username & {:keys [inline-help clear-disabled-helptips new-password]}]
  (navigate :named-user-page {:username username})
  (fill-form {:enable-inline-help-checkbox inline-help
              :clear-disabled-helptips clear-disabled-helptips
              :change-password-text new-password
              :confirm-password-text new-password}
             :save-user-edit #(browser sleep 1000))
  (check-for-success))

(defn create-role [name]
  (navigate :roles-tab)
  (->browser (click :new-role)
             (waitForElement :new-role-name-text "7500")) 
  (fill-form {:new-role-name-text name} :save-role #(browser sleep 1000)))

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
