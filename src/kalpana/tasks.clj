(ns  kalpana.tasks
  (:require [kalpana.locators]
            [com.redhat.qe.auto.navigate :as nav]
            [clojure.contrib.logging :as log])
  (:use [com.redhat.qe.auto.selenium.selenium :only [connect browser fill-form]]
        [error.handler :only [raise]]
        [com.redhat.qe.verify :only [verify]]))
;;tasks
(defn timestamp [s]
  (str s "-" (System/currentTimeMillis)))

(def known-errors {})

(defn matching-error "Returns a keyword of known error, if the message matches any of them."
  [message]
  (let [matches-message? (fn [key] (let [re (known-errors key)]
                                    (if (re-find re message) key false)))]
    (or (some matches-message? (keys known-errors))
	:kalpana-error)))

(defn check-for-error []
  (if (browser isElementPresent :error-message)
    (let [message (browser getText :error-message)]
      (raise {:type (matching-error message) :msg message}))))

(defn success-message []
  (if (browser isElementPresent :success-message)
    (browser getText :success-message) nil))

(def navigate (nav/nav-fn kalpana.locators/page-tree))

(defn create-organization [name description]
  (navigate :new-organization-page)
  (fill-form {:org-name-text name
              :org-description-text description}
             :create-organization))

(defn create-environment [org name description & {:keys [prior-env] :or {prior-env nil}}]
  (navigate :new-environment-page {:org-name org})
  (let [items {:env-name-text name
               :env-description-text description}]
    (fill-form (if prior-env (merge items {:prior-environment prior-env})
                   items)
              :create-environment)))

(defn create-content-provider [name description repo-url type username password]
  (navigate :new-content-provider-page)
  (fill-form {:cp-name-text name
              :cp-description-text description
              :cp-repository-url-text repo-url
              :cp-type-list  type
              :cp-username-text username
              :cp-password-text password}
             :cp-create-save))

(defn logout []
  (if (browser isElementPresent :log-in) (log/info "Already logged out.")
      (do (browser clickAndWait :log-out)
          (verify (= (success-message) "Logout Successful")))))

(defn login [username password]
  (if (browser isElementPresent :log-out)
    (do (log/warn "Already logged in, logging out.")
        (logout))
    (do (fill-form {:username-text username
                 :password-text password}
                   :log-in)
        )))
