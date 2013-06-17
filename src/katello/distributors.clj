(ns katello.distributors
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            [katello :as kt]
            [clojure.data :as data]
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
   ::details-link          "distributor_details"
   ::keyname-text          "new_custom_info_keyname"
   ::value-text            "new_custom_info_value"
   ::custom-info-button    "//input[@id='create_custom_info_button']"
   ::remove-link           (ui/remove-link "distributors")
   ::distributor-info-link (ui/third-level-link "distributor_info")
   ::events-history-link   (ui/third-level-link "distributor_events")
   ::custom-info-link      (ui/third-level-link "custom_info")
   ::save-button           "//button[@type='submit']"
   ::cancel-button         "//button[@type='cancel']"})

(sel/template-fns
 {value-text                   "custom_info[%s]"
  remove-custom-info-button    "//input[@data-id='custom_info_%s']"})

;; Nav

(nav/defpages :katello.deployment/any katello.menu
  [::page
   [::named-page (fn [distributor] (nav/choose-left-pane distributor))
    [::details-menu (nav/browser-fn (mouseOver ::details-link))
     [::distributor-info-page (nav/browser-fn (click ::distributor-info-link))]
     [::events-history-page (nav/browser-fn (click ::events-history-link))]
     [::custom-info-page (nav/browser-fn (click ::custom-info-link))]]]
   [::new-page (nav/browser-fn (click ::new))]])

;; Tasks

(defn- create
  "Creates a new distributor with the given name and environment."
  [{:keys [name env]}]
  {:pre [(instance? katello.Environment env)]}
  (nav/go-to ::new-page env)
  (browser click (ui/environment-link (:name env)))
  (sel/fill-ajax-form {::distributor-name-text name}
                      ::create)
  (notification/success-type :distributor-create))

(defn- update-dist-custom-info
  "Updates distributor's custom info"
  [to-add to-remove]
  (doseq [[k v] to-add]
    (if (and to-remove (to-remove k))
      (do (common/in-place-edit {(value-text k) v}))
      (do (sel/->browser 
            (setText ::keyname-text k)
            (setText ::value-text v)
            (keyUp ::keyname-text "z")
            (click ::custom-info-button))))) 
  ;; below dissoc required while updating, else will rm the just updated key/value
  (doseq [[k _] (apply dissoc to-remove (keys to-add))]
    (browser click (remove-custom-info-button k))))
  

(defn- delete
  "Deletes the named distributor."
  [dist]
  {:pre [(instance? katello.Distributor dist)]}
  (nav/go-to dist)
  (browser click ::remove-link)
  (browser click ::ui/confirmation-yes)
  (notification/success-type :distributor-destroy))

(defn- update
  "Updates the named distributor."
  [dist updated]
  {:pre [(instance? katello.Distributor dist)]}
  (let [[to-remove {:keys [name env keyname value]
                    :as to-add} _] (data/diff dist updated)]
    
    (when (or (:custom-info to-add) (:custom-info to-remove))
      (nav/go-to ::custom-info-page dist)
      (update-dist-custom-info (:custom-info to-add) (:custom-info to-remove)))))
        

(extend katello.Distributor
  ui/CRUD {:create create
           :delete delete
           :update* update}
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

