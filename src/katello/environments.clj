(ns katello.environments
  (:require [com.redhat.qe.auto.selenium.selenium :as sel]
            [com.redhat.qe.auto.selenium.selenium :refer [browser]]
            [slingshot.slingshot :refer [throw+ try+]]
            katello
            (katello [navigation :as nav]
                     [tasks :as tasks] 
                     [notifications :as notification] 
                     [ui :as ui]
                     [api-tasks :as api]
                     [rest :as rest]
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
  [{:keys [name label org description prior]}]
  (nav/go-to ::new-page {:org-name (:name org)})
  (sel/fill-ajax-form {::name-text name
                       (fn [label] (when label
                                     (browser fireEvent ::name-text "blur")
                                     (browser ajaxWait)
                                     (browser setText ::label-text label))) [label]
                       ::description-text description
                       ::prior (:name prior)}
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
  "Edits an environment. Passes env through f (with extra args) to get
  the new env."
  [{:keys [name org description] :as env} f & args]
  (nav/go-to ::named-page {:org-name (:name org)
                           :env-name name})
  (common/in-place-edit {::description-text (:description (apply f env args))}))

(let []
  (extend katello.Environment
    ui/CRUD {:create create
             :read (fn [_] (throw (Exception. "Read Not implemented on Environments")))
             :update edit
             :delete delete}
  
    api/CRUD
    (let [org-url (partial api/url-maker [["api/organizations/%s/environments/"] [:org]])
          id-url (partial api/url-maker [["api/organizations/%s/environments/%s"] [:org identity]])]
      {:id api/id-impl
       :query (partial api/query-by-name org-url)
       :create (fn [env] 
                 (merge env
                        (rest/post (org-url env)
                                   {:body
                                    {:environment
                                     {:name (:name env)
                                      :description (:description env)
                                      :prior (api/id (or (:prior env)
                                                         (assoc katello/library
                                                           :org (:org env))))}}})))
       :read (partial api/read-impl id-url)
     
       :update (fn [env f & args]
                 (let [updated (apply f env args)]
                   (merge updated (rest/put (id-url env)
                                            {:environment
                                             {:description (:description updated)}}))))})

    tasks/Uniqueable tasks/entity-uniqueable-impl))

(defn chain-envs
  "Sets prior of each env to be the previous env in the list"
  [environments]
  {:pre [(apply = (map :org environments))]} ; all in same org
  (for [[prior curr] (partition 2 1 (concat (list katello/library) environments))]
    (assoc curr :prior prior)))

(defn create-all
  "Creates multiple environments."
  [environments]
  (doseq [env environments]
    (ui/create env)))

(def create-path (comp create-all chain-envs))





