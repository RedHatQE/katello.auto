(ns katello.distributors
  (:require [webdriver :as browser]
            [katello :as kt]
            [katello.tests.useful :refer [third-lvl-menu-click]]
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
   ::create                {:name "commit"}
   ::distributor-name-text {:tag :input, :name "distributor[name]"}
   ::details-link          {:id "distributor_details"}
   ::keyname-text          "new_custom_info_keyname"
   ::value-text            "new_custom_info_value"
   ::custom-info-button    "//input[@id='create_custom_info_button']"
   ::remove-link           (ui/remove-link "distributors")
   ::distributor-info-link (ui/third-level-link "distributor_info")
   ::events-history-link   (ui/third-level-link "distributor_events")
   ::custom-info-link      (ui/third-level-link "custom_info")
   ::save-button           "//button[@type='submit']"
   ::cancel-button         "//button[@type='cancel']"})

(browser/template-fns
 {value-text                   "custom_info[%s]"
  remove-custom-info-button    "//input[@data-id='custom_info_%s']"})

;; Nav

(nav/defpages :katello.deployment/any katello.menu
  [::page
   [::named-page (fn [distributor] (nav/choose-left-pane distributor))
    [::details-menu (fn [_] (browser/move-to ::details-link)
                            (Thread/sleep 3000))
     [::distributor-info-page (fn [_] (third-lvl-menu-click ::distributor-info-link))]
     [::events-history-page (fn [_] (third-lvl-menu-click ::events-history-link))]
     [::custom-info-page (fn [_] (third-lvl-menu-click ::custom-info-link))]]]
   [::new-page (fn [_] (browser/click ::new))]])

;; Tasks

(defn- create
  "Creates a new distributor with the given name and environment."
  [{:keys [name env]}]
  {:pre [(instance? katello.Environment env)]}
  (nav/go-to ::new-page env)
  (rest/when-katello
   (browser/click (ui/environment-link (:name env))))
  (browser/quick-fill [::distributor-name-text name
                       ::create browser/click])
  (notification/success-type :distributor-create))

(defn- update-dist-custom-info
  "Updates distributor's custom info"
  [to-add to-remove]
  (doseq [[k v] to-add]
    (if (and to-remove (to-remove k))
      (do (common/in-place-edit {(value-text k) v}))
      (do (browser/input-text ::keyname-text k)
          (browser/input-text ::value-text v)
          (browser/click ::custom-info-button))))
  ;; below dissoc required while updating, else will rm the just updated key/value
  (doseq [[k _] (apply dissoc to-remove (keys to-add))]
    (browser/click (remove-custom-info-button k))))


(defn- delete
  "Deletes the named distributor."
  [dist]
  {:pre [(instance? katello.Distributor dist)]}
  (nav/go-to dist)
  (browser/click ::remove-link)
  (browser/click ::ui/confirmation-yes)
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
                  headpin-url (partial rest/url-maker [["api/organizations/%s/distributors" [#'kt/org]]])
                  katello-url (partial rest/url-maker [["api/environments/%s/distributors" [#'kt/env]]])]
              {:id rest/id-field
               :query (fn [dist]
                        (rest/query-by-name
                         (if (rest/is-katello?)
                           katello-url headpin-url) dist))
               :create (fn [dist]
                         (merge dist
                                (rest/http-post
                                 (if (rest/is-katello?)
                                   (katello-url dist)
                                   (headpin-url dist))
                                 {:body (assoc (select-keys dist [:name])
                                          :type "distributor")})))
               :read (partial rest/read-impl id-url)})

  tasks/Uniqueable tasks/entity-uniqueable-impl
  nav/Destination {:go-to (partial nav/go-to ::named-page)})

(defn new-button-disabled?
  "Returns true if the new distributor button is disabled and the correct message is shown"
  [org]
  (nav/go-to ::new-page org)
  (and (.contains (browser/attribute ::new :class) "disabled")
       (.contains (browser/attribute ::new "original-title") "environment is required")))
