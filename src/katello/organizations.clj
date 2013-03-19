(ns katello.organizations
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser ->browser]]
            [ui.navigate :as navlib :refer [nav-tree]]
            (katello [navigation :as nav]
                     [ui :as ui]
                     [ui-common :as common]
                     [notifications :as notification]
                     [conf :refer [*session-org* with-org]]))
  (:import [com.thoughtworks.selenium SeleniumException]))

;; Locators

(ui/deflocators
  {::new                    "//a[@id='new']"
   ::create                 "organization_submit"
   ::name-text              "organization[name]"
   ::label-text             "organization[label]"
   ::description-text       "organization[description]"
   ::environments           (ui/link "Environments")
   ::edit                   (ui/link "Edit")
   ::remove                 (ui/link "Remove Organization")
   ::initial-env-name-text  "environment_name"
   ::initial-env-label-text "environment_label"
   ::initial-env-desc-text  "environment_description"
   ::access-dashboard       ""
   ::active                 "//*[@id='switcherButton']"
   ::default                "//div[@id='orgbox']//input[@checked='checked' and @class='default_org']/../"})

;; Nav

(nav/defpages (common/pages)
  [::page 
   [::new-page [] (browser click ::new)]
   [::named-page [org-name] (nav/choose-left-pane  org-name)]])

;; Tasks

(defn label-filler
  "Fills in the label field which has special js sauce that prevents
   it from being writable unless the name field has been blurred."
  [name-loc label-loc label-text]
  (when label-text
    (->browser (fireEvent name-loc "blur")
               (ajaxWait)
               (setText label-loc label-text))))

(defn create
  "Creates an organization with the given name and optional description."
  [name & [{:keys [label description initial-env-name initial-env-label initial-env-description]}]]
  (nav/go-to ::new-page)
  (sel/fill-ajax-form [::name-text name
                       label-filler [::name-text ::label-text label]
                       ::description-text description
                       ::initial-env-name-text initial-env-name
                       label-filler [::initial-env-name-text ::initial-env-label-text initial-env-label]
                       ::initial-env-desc-text initial-env-description]
                      ::create)
  (notification/check-for-success {:match-pred (notification/request-type? :org-create)}))

(defn delete
  "Deletes the named organization."
  [org-name]
  (nav/go-to ::named-page {:org-name org-name})
  (browser click ::remove)
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success
   {:match-pred (notification/request-type? :org-destroy)})   ;queueing success
  (browser refresh)
  (notification/check-for-success {:timeout-ms (* 20 60 1000)})) ;for actual delete

(defn edit
  "Edits an organization. Currently the only property of an org that
   can be edited is the org's description."
  [org-name & {:keys [description]}]
  (nav/go-to ::named-page {:org-name org-name})
  (common/in-place-edit {::description-text description}))

(defn current
  "Return the currently active org (a string) shown in the org switcher."
  []
  (nav/go-to ::nav/top-level)
  ((->> ::active (browser getAttributes) (into {})) "title"))

(defn switch
  "Switch to the given organization in the UI. If no args are given,
   the value of *session-org* is used. If force? is true,switch even
   if the org switcher is already on the requested org. Optionally
   also select the default org for this user. To remove any default
   org for this user, set default org to :none. Using force is not
   necessary if also setting the default-org."
  ([] (switch *session-org*))
  ([org-name & [{:keys [force? default-org login?]}]]
     (if-not login? (nav/go-to ::nav/top-level)) 
     (when (or force?
               login?
               default-org
               (not= (current) org-name))       
       (browser click ::ui/switcher)
       (when default-org
         (let [current-default (try (browser getText ::default)
                                    (catch SeleniumException _ :none))]
           (when (not= current-default default-org)
             (browser click (ui/default-star (if (= default-org :none)
                                               current-default
                                               default-org)))
             (notification/check-for-success))))
       (when org-name
         (browser clickAndWait (ui/switcher-link org-name))))))

(defn before-test-switch
  "A pre-made fn to switch to the session org, meant to be used in
   test groups as a :test-setup."
  [& _]
  (switch))
