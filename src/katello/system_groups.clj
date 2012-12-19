(ns katello.system-groups
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            [clojure.string :refer [blank?]]
            [test.assert :as assert]
            (katello [navigation :as nav]
                     [systems :as system]
                     [notifications :as notification]
                     [ui :as ui]
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
   [::new-page [] (browser click ::new)]
   [::named-page [system-group-name] (nav/choose-left-pane system-group-name)
    [::systems-page [] (browser click ::systems-link)]
    [::details-page [] (browser click ::details-link)]]])


;; Tasks

(defn create
  "Creates a system group"
  [name & [{:keys [description]}]]
  (nav/go-to ::new-page)
  (sel/fill-ajax-form
   {::name-text name
    ::description-text description}
   ::create)
  (notification/check-for-success {:match-pred (notification/request-type? :sysgrps-create)}))

(defn add-to
  "Adds a system to a system group"
  [group system]
  (nav/go-to ::named-page {:system-group-name group})
  (comment (browser setText ::hostname-toadd system)
           (browser typeKeys ::hostname-toadd " ")
           (Thread/sleep 5000)
           (browser click ::add-system)
           (check-for-success))
  (sel/fill-ajax-form [::hostname-toadd system
                       ;;try to trigger autocomplete via javascript -
                       ;;hackalert - see
                       ;;https://bugzilla.redhat.com/show_bug.cgi?id=865472 -jweiss
                       #(browser getEval %) ["window.$(\"#add_system_input\").autocomplete('search')"]
                       #(Thread/sleep 5000) []]
                      ::add-system)
  (notification/check-for-success {:match-pred (notification/request-type? :sysgrps-add-sys)}))

(defn remove-from
  "Remove a system from a system group"
  [group system]
  (nav/go-to ::named-page {:system-group-name group})
  (browser click (system/checkbox system))
  (browser click ::remove-system))

(defn copy
  "Clones a system group, given the name of the original system group
   to clone, and the new name and description."
  [orig-name new-name & [{:keys [description]}]]
  (nav/go-to ::named-page {:system-group-name orig-name})
  (browser click ::copy)
  (sel/fill-ajax-form {::copy-name-text new-name
                       ::copy-description-text description}
                      ::copy-submit)
  (notification/check-for-success {:match-pred (notification/request-type? :sysgrps-copy)}))

(defn remove
  "Removes a system group. Optionally, remove all the systems in the
   group as well."
  [group & [{:keys [also-remove-systems?]}]]
  (nav/go-to ::named-page {:system-group-name group})
  (browser click ::remove)
  (browser click ::ui/confirmation-yes)
  (browser click (if also-remove-systems?
                   ::ui/confirmation-yes
                   ::confirm-only-group))
  (notification/check-for-success
   {:match-pred  (notification/request-type? (if also-remove-systems?
                                               :sysgrps-destroy-sys
                                               :sysgrps-destroy))}))

(defn edit "Change the value of limit field in system group"
  [sg-name {:keys [new-limit new-sg-name description]}]
  (nav/go-to ::details-page {:system-group-name sg-name})
  (let [needed-flipping (and new-limit
                             (not= (= new-limit :unlimited)
                                   (browser isChecked ::unlimited-checkbox)))]
    (if (and new-limit (not= new-limit :unlimited))
      (do (browser uncheck ::unlimited-checkbox)
          (sel/fill-ajax-form {::limit-value (str new-limit)}
                              ::save-new-limit ))
      (browser check ::unlimited-checkbox))
    (when needed-flipping (notification/check-for-success
                           {:match-pred (notification/request-type? :sysgrps-update)})))
  (common/in-place-edit {::name-text new-sg-name
                         ::description-text description}))

(defn system-count
  "Get number of systems in system group according to the UI"
  [sg-name]
  (nav/go-to ::details-page {:system-group-name sg-name})
  (Integer/parseInt (browser getText ::total)))
