(ns katello.system-groups
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            [clojure.string :refer [blank?]]
            [clojure.data :as data]
            [test.assert :as assert]
            [katello :as kt]
            (katello [navigation :as nav]
                     [systems :as system]
                     [notifications :as notification]
                     [ui :as ui]
                     [tasks :refer [when-some-let] :as tasks]
                     [ui-common :as common]))
  (:refer-clojure :exclude [remove]))


(ui/deflocators
       {::new                   "//a[@id='new']"
        ::create                "group_save"
        ::name-text             "system_group[name]"
        ::description-text      "system_group[description]"
        ::systems-link          (ui/menu-link "system_groups_systems")
        ::details-link          (ui/menu-link "system_group_details")
        ::hostname-toadd        "add_system_input"
        ::add-system            "add_system"
        ::remove-system         "remove_systems"
        ::copy                  (ui/link "Copy")
        ::copy-name-text        "name_input"
        ::copy-description-text "description_input"
        ::copy-submit           "copy_button"
        ::cancel-copy           "cancel_copy_button"
        ::close                 (ui/link "Close")
        ::remove                (ui/link "Remove")
        ::total                 "//fieldset[contains(.,'Total')]/div[2]/a"
        ::confirm-only-group    "//span[.='No, only delete the system group.']"
        ::unlimited-checkbox    "//input[@class='unlimited_members']"
        ::save-new-limit        "//button[.='Save']"
        ::limit-value           "system_group[max_systems]"}
       ui/locators)

;; Nav

(nav/defpages (common/pages)
  [::page
   [::new-page (nav/browser-fn (click ::new))]
   [::named-page (fn [system-group] (nav/choose-left-pane system-group))
    [::systems-page (nav/browser-fn (click ::systems-link))]
    [::details-page (nav/browser-fn (click ::details-link))]]])


;; Tasks

(defn create
  "Creates a system group"
  [{:keys [name description] :as sg}]
  (nav/go-to ::new-page sg)
  (sel/fill-ajax-form
   {::name-text name
    ::description-text description}
   ::create)
  (notification/check-for-success {:match-pred (notification/request-type? :sysgrps-create)}))

(defn- add-to
  "Adds systems to a system group"
  [systems]
  (browser click ::systems-link)
  (doseq [system systems]
    (sel/fill-ajax-form [::hostname-toadd (:name system)
                         ;;try to trigger autocomplete via javascript -
                         ;;hackalert - see
                         ;;https://bugzilla.redhat.com/show_bug.cgi?id=865472 -jweiss
                         #(browser getEval %) ["window.$(\"#add_system_input\").autocomplete('search')"]
                         #(Thread/sleep 5000) []]
                        ::add-system)
    (notification/check-for-success {:match-pred (notification/request-type? :sysgrps-add-sys)})))

(defn- remove-from
  "Remove systems from a system group"
  [systems]
  (browser click ::systems-link)
  (doseq [system systems]
    (browser click (system/checkbox (:name system)))
    (browser click ::remove-system)))

(defn copy
  "Clones a system group, given the original system group to clone,
   and another system group record (from which the name and
   description will be taken for the clone)."
  [orig clone]
  (nav/go-to orig)
  (browser click ::copy)
  (sel/fill-ajax-form {::copy-name-text (:name clone)
                       ::copy-description-text (:description clone)}
                      ::copy-submit)
  (notification/check-for-success {:match-pred (notification/request-type? :sysgrps-copy)}))

(defn remove
  "Removes a system group. Optionally, remove all the systems in the
   group as well."
  [{:keys [also-remove-systems?] :as group}]
  (nav/go-to group)
  (browser click ::remove)
  (browser click ::ui/confirmation-yes)
  (browser click (if also-remove-systems?
                   ::ui/confirmation-yes
                   ::confirm-only-group))
  (notification/check-for-success
   {:match-pred  (notification/request-type? (if also-remove-systems?
                                               :sysgrps-destroy-sys
                                               :sysgrps-destroy))}))

(defn- edit-details
  "Change the name, description and limit in system group"
  [name description limit]
  (browser click ::details-link)
  (let [needed-flipping (and limit
                             (not= (= limit :unlimited)
                                   (browser isChecked ::unlimited-checkbox)))]
    (if (and limit (not= limit :unlimited))
      (do (browser uncheck ::unlimited-checkbox)
          (sel/fill-ajax-form {::limit-value (str limit)}
                              ::save-new-limit ))
      (browser check ::unlimited-checkbox))
    (when needed-flipping (notification/check-for-success
                           {:match-pred (notification/request-type? :sysgrps-update)})))
  (common/in-place-edit {::name-text name
                         ::description-text description}))

(defn system-count
  "Get number of systems in system group according to the UI"
  [group]
  (nav/go-to ::details-page group)
  (Integer/parseInt (browser getText ::total)))

(defn update [sg updated]
  (nav/go-to sg)
  (let [[remove add] (data/diff sg updated)]
    (when-some-let [{:keys [name description limit]} add]
                   (edit-details name description limit))
    (when-some-let [sys-to-add (:systems add)
                    sys-to-rm (:systems remove)]
                   (add-to sys-to-add)
                   (remove-from sys-to-rm))))

(extend katello.SystemGroup
  ui/CRUD {:create create
           :delete remove
           :update* update}

  tasks/Uniqueable tasks/entity-uniqueable-impl
  nav/Destination {:go-to (partial nav/go-to ::named-page)})
