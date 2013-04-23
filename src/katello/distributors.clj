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

(ui/deflocators
  {::new                   "new"
   ::create                "commit"
   ::distributor-name-text "distributor[name]"})

;; Nav

(nav/defpages (common/pages))

;; Tasks

(defn create-distributor
  "Creates a new distributor with the given name and environment."
  [{:keys [name env]}]
  {:pre [(instance? katello.Environment env)]}
  (nav/go-to ::distributors-page {:org (:org env)})
  (browser click ::new)
  (browser click (ui/environment-link (:name env)))
  (sel/fill-ajax-form {::distributor-name-text name}
                      ::create)
  (notification/check-for-success {:match-pred (notification/request-type? :distributor-create)}))

(defn delete-distributor
  "Deletes the named distributor."
  [dist]
  {:pre [(instance? katello.Distributor dist)]}
  (nav/go-to dist))

(extend katello.Distributor
  ui/CRUD {:create create-distributor}
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
               :read (partial rest/read-impl id-url)}))

(defn new-distributor-button-disabled?
  "Returns true if the new distributor button is disabled and the correct message is shown"
  [org]
  (nav/go-to ::distributors-page {:org org})
  (-> (browser getAttributes ::new-distributor-disabled)
      (get "original-title")
      (= "At least one environment is required to create or register distributors in your current organization.")))

