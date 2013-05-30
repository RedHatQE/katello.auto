(ns katello.distributors
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            [katello :as kt]
            (katello [ui :as ui]
                     [rest :as rest]
                     [navigation :as nav]
                     [tasks :as tasks]
                     [notifications :as notification]
                     [ui-common :as common]
                     [manifest :as manifest])))

;; Locators

(ui/defelements :katello.deployment/any []
  {::new                   "new"
   ::create                "commit"
   ::distributor-name-text "distributor[name]"
   ::remove-link           (ui/remove-link "distributors")})

;; Nav

(nav/defpages :katello.deployment/any katello.menu
  [::page
   [::named-page (fn [distributor] (nav/choose-left-pane distributor))]
   [::new-page (nav/browser-fn (click ::new))]])

;; Tasks

(defn create
  "Creates a new distributor with the given name and environment."
  [{:keys [name env]}]
  {:pre [(instance? katello.Environment env)]}
  (nav/go-to ::new-page env)
  (browser click (ui/environment-link (:name env)))
  (sel/fill-ajax-form {::distributor-name-text name}
                      ::create)
  (notification/check-for-success {:match-pred (notification/request-type? :distributor-create)}))

(defn delete
  "Deletes the named distributor."
  [dist]
  {:pre [(instance? katello.Distributor dist)]}
  (nav/go-to dist)
  (browser click ::remove-link)
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success {:match-pred (notification/request-type? :distributor-destroy)}))

(extend katello.Distributor
  ui/CRUD {:create create
           :delete delete}
  rest/CRUD (let [id-url (partial rest/url-maker [["api/distributors/%s" [identity]]])
                  query-urls (partial rest/url-maker [["api/environments/%s/distributors" [:env]]
                                                      ["api/organizations/%s/distributors" [(comp :org :env)]]])]
              {:id rest/id-field
               :query (partial rest/query-by-name query-urls)
               :create (fn [dist]
                         (merge dist
                                (rest/http-post
                                 (rest/url-maker [["api/environments/%s/distributors" [:env]]] dist)
                                 {:body (assoc (select-keys dist [:name])
                                          :type "distributor")})))
               :read (partial rest/read-impl id-url)})

  tasks/Uniqueable tasks/entity-uniqueable-impl
  nav/Destination {:go-to (partial nav/go-to ::named-page)})

(defn new-button-disabled?
  "Returns true if the new distributor button is disabled and the correct message is shown"
  [org]
  (nav/go-to ::new-page org)
  (let [{:strs [original-title class]} (browser getAttributes ::new)]
    (and (.contains class "disabled")
         (.contains original-title "environment is required"))))

