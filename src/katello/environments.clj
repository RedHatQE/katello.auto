(ns katello.environments
  (:require [webdriver :as browser]
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
  {::name-text         {:tag :input, :name "kt_environment[name]"}
   ::label-text        {:tag :input, :name "kt_environment[label]"}
   ::description-text  {:tag :textarea, :name "kt_environment[description]"}
   ::prior             {:name "kt_environment[prior]"}
   ::create            {:name "commit"}
   ::new               "//form[@id='organization_edit']//div[contains(@data-url, '/environments/new')]"
   ::remove-link       (ui/remove-link "environments")})

;; Nav

(nav/defpages :katello.deployment/any katello.organizations
  [:katello.organizations/named-page
   [::new-page (fn [_] (browser/click ::new))]
   [::named-page (fn [env] (browser/click (ui/environment-link (:name env))))]])

;; Tasks

(defn- create
  "Creates an environment with the given name, and a map containing
   the organization name to create the environment in, the prior
   environment, and an optional description."
  [{:keys [name label org description prior]}]
  (nav/go-to ::new-page org)
  (Thread/sleep 2000)
  (browser/quick-fill [::name-text name
                       ::description-text description])
  (let [label-activate #(webdriver/execute-script "$('.name_input').trigger('blur')")] ;; workaround to activate label js
    (when label
      (label-activate) 
      (browser/clear ::label-text)
      (browser/input-text ::label-text label)))
  (browser/quick-fill [::prior #(browser/select-by-text % (:name prior))
                       ::create browser/click])
  (notification/success-type :env-create))

(defn- delete
  "Deletes an environment from the given organization."
  [env]
  (nav/go-to env)
  (if (browser/exists? ::remove-link)
    (browser/click ::remove-link)
    (throw+ {:type ::cant-be-deleted :env env}))
  (browser/click ::ui/confirmation-yes)
  (notification/success-type :env-destroy))

(defn- edit
  "Edits an environment. Passes env through f (with extra args) to get
  the new env."
  [env {:keys [description]}]
  (nav/go-to env)
  (common/in-place-edit {::description-text description}))


(extend katello.Environment
  ui/CRUD {:create (rest/only-when-katello create)
           :update* (rest/only-when-katello edit)
           :delete (rest/only-when-katello delete)}

  rest/CRUD
  (let [org-url (partial rest/url-maker [["api/organizations/%s/environments" [#'katello/org]]])
        id-url (partial rest/url-maker [["api/organizations/%s/environments/%s" [:org identity]]])]
    {:id rest/id-field
     :query (partial rest/query-by-name org-url)
     :create (fn [env]
               (if (rest/is-katello?)
                 (merge env
                        (rest/http-post (org-url env)
                                        {:body
                                         {:environment
                                          {:name (:name env)
                                           :description (:description env)
                                           :prior (rest/get-id (or (:prior env)
                                                                   (katello/mklibrary env)))}}}))))
     :read (fn [env]
             (if (rest/is-katello?)
               (rest/read-impl id-url env)
               true)) ;; hack to make rest/exists? think that env's in a record exists for headpin.

     :update* (fn [env new-env]
                (merge new-env (rest/http-put (id-url env)
                                              {:environment (select-keys new-env [:description])})))})

  tasks/Uniqueable tasks/entity-uniqueable-impl

  nav/Destination {:go-to (partial nav/go-to ::named-page)})
