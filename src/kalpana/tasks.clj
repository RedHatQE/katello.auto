(ns  kalpana.tasks
  (:require [kalpana.locators])
  (:use [com.redhat.qe.auto.selenium.selenium :only [connect browser]]
        [error.handler :only [raise]]))
;;tasks

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

(defn navigate-to-tab [& tabs]
  (doall (for [tab tabs] (browser clickAndWait tab))))

(defn create-organization [name description]
  (navigate-to-tab :organizations)
  (browser clickAndWait :new-organization)
  (browser setText :org-name-text name)
  (browser setText :org-description-text description)
  (browser clickAndWait :create-organization))

(defn create-environment [org name description & {:keys [prior-env] :or {prior-env nil}}]
  (browser open (str "/organizations/" org))
  (browser clickAndWait :org-environments)
  (browser clickAndWait :new-environment)
  (browser setText :env-name-text name)
  (browser setText :env-description-text description)
  (if prior-env
    (browser select :prior-environment prior-env))
  (browser clickAndWait :create-environment))

(defn login [username password]
  (browser setText :username-text username)
  (browser setText :password-text password)
  (browser clickAndWait :log-in))

