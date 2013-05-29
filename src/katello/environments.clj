(ns katello.environments
  (:require [com.redhat.qe.auto.selenium.selenium :as sel]
            [com.redhat.qe.auto.selenium.selenium :refer [browser]]
            [slingshot.slingshot :refer [throw+ try+]]
            [katello :as kt]
            (katello [navigation :as nav]
                     [tasks :as tasks] 
                     [notifications :as notification] 
                     [ui :as ui]
                     [rest :as rest]
                     [ui-common :as common]
                     [organizations :as org]))) 

;; Locators

(ui/defelements :katello.deployment/any [katello.ui]
  {::name-text         "kt_environment[name]"
   ::label-text        "kt_environment[label]"
   ::description-text  "kt_environment[description]"
   ::prior             "kt_environment[prior]"
   ::create            "commit"
   ::new               "//form[@id='organization_edit']//div[contains(@data-url, '/environments/new')]"
   ::remove-link       (ui/remove-link "environments")
   ::prior-select-edit "kt_environment[prior]" })

;; Nav

(nav/defpages :katello.deployment/any katello.organizations
  [:katello.organizations/named-page
   [::new-page (nav/browser-fn (click ::new))]
   [::named-page (fn [env] (browser click (ui/environment-link (:name env))))]])

;; Tasks

(defn- create
  "Creates an environment with the given name, and a map containing
   the organization name to create the environment in, the prior
   environment, and an optional description."
  [{:keys [name label org description prior]}]
  (nav/go-to ::new-page org)
  (sel/fill-ajax-form {::name-text name
                       (fn [label] (when label
                                     (browser fireEvent ::name-text "blur")
                                     (browser ajaxWait)
                                     (browser setText ::label-text label))) [label]
                       ::description-text description
                       ::prior (:name prior)}
                      ::create)
  (notification/check-for-success {:match-pred (notification/request-type? :env-create)}))

(defn- delete
  "Deletes an environment from the given organization."
  [env]
  (nav/go-to env)
  (if (browser isElementPresent ::remove-link)
    (browser click ::remove-link)
    (throw+ {:type ::cant-be-deleted :env env}))
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success {:match-pred (notification/request-type? :env-destroy)}))

(defn- edit
  "Edits an environment. Passes env through f (with extra args) to get
  the new env."
  [env {:keys [description]}]
  (nav/go-to env)
  (common/in-place-edit {::description-text description}))

(extend katello.Environment
  ui/CRUD {:create create
           :update* edit
           :delete delete}
  
  rest/CRUD
  (let [org-url (partial rest/url-maker [["api/organizations/%s/environments/" [:org]]])
        id-url (partial rest/url-maker [["api/organizations/%s/environments/%s" [:org identity]]])]
    {:id rest/id-field
     :query (partial rest/query-by-name org-url)
     :create (fn [env] 
               (merge env
                      (rest/http-post (org-url env)
                                 {:body
                                  {:environment
                                   {:name (:name env)
                                    :description (:description env)
                                    :prior (rest/get-id (or (:prior env)
                                                        (katello/mklibrary env)))}}})))
     :read (partial rest/read-impl id-url)
     
     :update* (fn [env new-env]
               (merge new-env (rest/http-put (id-url env)
                                             {:environment (select-keys new-env [:description])})))})

  tasks/Uniqueable tasks/entity-uniqueable-impl

  nav/Destination {:go-to (partial nav/go-to ::named-page)})


