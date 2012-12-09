(ns katello.environments
  (:require [com.redhat.qe.auto.selenium.selenium :refer [browser]]
            [slingshot.slingshot :refer [throw+ try+]]
            (katello [navigation :as nav]
                     [tasks :refer [library]] 
                     [notifications :as notification] 
                     [ui-common :as ui])))

;; Locators

(swap! ui/locators merge
       {::name-text         "kt_environment[name]"
        ::label-text        "kt_environment[label]"
        ::description-text  "kt_environment[description]"
        ::prior             "kt_environment[prior]"
        ::create            "//input[@value='Create']"
        ::new               "//div[normalize-space(.)='Add New Environment']"
        ::remove            (ui/link "Remove Environment")
        ::prior-select-edit "kt_environment[prior]" })

(nav/graft :katello.organizations/named-page [::new-page [] (browser click ::new)])
(nav/graft :katello.organizations/named-page [::named-page [env-name] (browser click (ui/environment-link env-name))])

;; Tasks

(defn create
  "Creates an environment with the given name, and a map containing
   the organization name to create the environment in, the prior
   environment, and an optional description."
  [name {:keys [org-name description prior-env]}]
  (nav/go-to ::new-page {:org-name org-name})
  (fill-ajax-form {::name-text name
                   ::description-text description
                   ::prior prior-env}
                  ::create)
  (notification/check-for-success {:match-pred (notification/request-type? :env-create)}))

(defn delete
  "Deletes an environment from the given organization."
  [env-name {:keys [org-name]}]
  (nav/go-to ::named-page {:org-name org-name
                           :env-name env-name})
  (if (browser isElementPresent ::remove)
    (browser click ::remove)
    (throw+ {:type :env-cant-be-deleted :env-name env-name}))
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success {:match-pred (notification/request-type? :env-destroy)}))

(defn edit
  "Edits an environment with the given name. Also takes a map
   containing the name of the environment's organization, and optional
   fields: a new description."
  [env-name {:keys [org-name description]}]
  (nav/go-to ::named-page {:org-name org-name
                           :env-name env-name})
  (in-place-edit {::description-text description}))

(defn create-path
  "Creates a path of environments in the given org. All the names in
  the environment list must not already exist in the given org. Example:
  (create-path 'ACME_Corporation' ['Dev' 'QA' 'Production'])"
  [org-name environments]
  (let [env-chain  (partition 2 1 (concat [library] environments))]
    (doseq [[prior curr] env-chain]
      (create curr {:prior-env prior
                    :org-name org-name}))))





