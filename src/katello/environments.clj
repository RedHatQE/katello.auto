(ns katello.environments
  (:require [com.redhat.qe.auto.selenium.selenium :as sel]
            [com.redhat.qe.auto.selenium.selenium :refer [browser]]
            [slingshot.slingshot :refer [throw+ try+]]
            katello
            (katello [navigation :as nav]
                     [tasks :as tasks] 
                     [notifications :as notification] 
                     [ui :as ui]
                     [ui-common :as common]
                     [organizations :as org]))) 

;; Locators

(ui/deflocators
  {::name-text         "kt_environment_name"
   ::label-text        "kt_environment_label"
   ::description-text  "kt_environment_description"
   ::prior             "kt_environment_prior"
   ::create            "kt_environment_submit"
   ::new               "//div[@id='organization_edit']//div[contains(@data-url, '/environments/new')]"
   ::remove-link       (ui/remove-link "environments")
   ::prior-select-edit "kt_environment_prior" }
  ui/locators)

;; Nav

(nav/defpages (org/pages)
  [:katello.organizations/named-page
   [::new-page [] (browser click ::new)]
   [::named-page [env-name] (browser click (ui/environment-link env-name))]])

;; Tasks

(defn create
  "Creates an environment with the given name, and a map containing
   the organization name to create the environment in, the prior
   environment, and an optional description."
  [{:keys [name label org description prior-env]}]
  (nav/go-to ::new-page {:org-name (:name org)})
  (sel/fill-ajax-form {::name-text name
                       (fn [label] (when label
                                     (browser fireEvent ::name-text "blur")
                                     (browser ajaxWait)
                                     (browser setText ::label-text label))) [label]
                       ::description-text description
                       ::prior (:name prior-env)}
                      ::create)
  (notification/check-for-success {:match-pred (notification/request-type? :env-create)}))

(defn delete
  "Deletes an environment from the given organization."
  [{:keys [name org]}]
  (nav/go-to ::named-page {:org-name (:name org)
                           :env-name name})
  (if (browser isElementPresent ::remove-link)
    (browser click ::remove-link)
    (throw+ {:type ::cant-be-deleted :env-name name}))
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success {:match-pred (notification/request-type? :env-destroy)}))

(defn edit
  "Edits an environment with the given name. Also takes a map
   containing the name of the environment's organization, and optional
   fields: a new description."
  [{:keys [name org description]}]
  (nav/go-to ::named-page {:org-name (:name org)
                           :env-name name})
  (common/in-place-edit {::description-text description}))

(extend katello.Environment
  ui/CRUD {:create create
           :read (fn [_] (throw (Exception. "Read Not implemented on Environments")))
           :update edit
           :delete delete}
  tasks/Uniqueable tasks/entity-uniqueable-impl)

(defn chain-envs
  "Sets prior of each env to be the previous env in the list"
  [environments]
  {:pre [(apply = (map :org environments))]}
  (for [[prior curr] (partition 2 1 (concat (list katello/library) environments))]
    (assoc curr :prior-env prior)))

(defn create-all
  "Creates a path of environments. All the names in the environment
  list must not already exist in their respective org. Example:
  (create-path 'ACME_Corporation' ['Dev' 'QA' 'Production'])"
  [environments]
  {:pre [(apply = (map :org environments))]}
  (doseq [env environments]
    (ui/create env)))

(def create-path (comp create-all chain-envs))





