(ns katello.environments
  (:require [com.redhat.qe.auto.selenium.selenium :refer [browser]]
            [slingshot.slingshot :refer [throw+ try+]]
            (katello [navigation :as nav]
                     [locators :as locators] 
                     [tasks :refer [library]] 
                     [notifications :as notification] 
                     [ui-tasks :refer [navigate fill-ajax-form in-place-edit]])))

;;
;; Environments
;;

;; Locators

(swap! locators/uimap merge
  {:env-name-text             "kt_environment[name]"
   :env-label-text             "kt_environment[label]"
   :env-description-text      "kt_environment[description]"
   :prior-environment         "kt_environment[prior]"
   :create-environment        "//input[@value='Create']"
   :new-environment           "//div[normalize-space(.)='Add New Environment']"
   :remove-environment        (locators/link "Remove Environment")
   :env-prior-select-edit     "kt_environment[prior]" })

;; Tasks

(defn create
  "Creates an environment with the given name, and a map containing
   the organization name to create the environment in, the prior
   environment, and an optional description."
  [name {:keys [org-name description prior-env]}]
  (nav/go-to :new-environment-page {:org-name org-name})
  (fill-ajax-form {:env-name-text name
                   :env-description-text description
                   :prior-environment prior-env}
                  :create-environment)
  (notification/check-for-success {:match-pred (notification/request-type? :env-create)}))

(defn delete
  "Deletes an environment from the given organization."
  [env-name {:keys [org-name]}]
  (nav/go-to :named-environment-page {:org-name org-name
                                     :env-name env-name})
  (if (browser isElementPresent :remove-environment)
    (browser click :remove-environment)
    (throw+ {:type :env-cant-be-deleted :env-name env-name}))
  (browser click :confirmation-yes)
  (notification/check-for-success {:match-pred (notification/request-type? :env-destroy)}))

(defn edit
  "Edits an environment with the given name. Also takes a map
   containing the name of the environment's organization, and optional
   fields: a new description."
  [env-name {:keys [org-name description]}]
  (nav/go-to :named-environment-page {:org-name org-name
                                     :env-name env-name})
  (in-place-edit {:env-description-text description}))

(defn create-path
  "Creates a path of environments in the given org. All the names in
  the environment list must not already exist in the given org. Example:
  (create-path 'ACME_Corporation' ['Dev' 'QA' 'Production'])"
  [org-name environments]
  (let [env-chain  (partition 2 1 (concat [library] environments))]
    (doseq [[prior curr] env-chain]
      (create curr {:prior-env prior
                                :org-name org-name}))))





