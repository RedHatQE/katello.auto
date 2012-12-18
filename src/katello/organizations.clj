(ns katello.organizations
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            [ui.navigate :as navlib :refer [nav-tree]]
            (katello [navigation :as nav]
                     [ui :as ui]
                     [ui-common :as common]
                     [notifications :as notification]
                     [conf :refer [*session-org* with-org]]))
  (:import [com.thoughtworks.selenium SeleniumException]))

;; Locators

(sel/template-fns
 {default-star  "//div[@id='orgbox']//a[.='%s']/../span[starts-with(@id,'favorite')]"
  switcher-link "//div[@id='orgbox']//a[.='%s']"})

(ui/deflocators {::new                   "//a[@id='new']"
                 ::create                "organization_submit"
                 ::name-text             "organization[name]"
                 ::description-text      "organization[description]"
                 ::environments          (ui/link "Environments")
                 ::edit                  (ui/link "Edit")
                 ::remove                (ui/link "Remove Organization")
                 ::initial-env-name-text "environment[name]"
                 ::initial-env-desc-text "environment[description]"
                 ::switcher              "switcherButton"
                 ::manage-switcher-link  "manage_orgs"
                 ::active                "//*[@id='switcherButton']"
                 ::default               "//div[@id='orgbox']//input[@checked='checked' and @class='default_org']/../"})

;; Nav

(nav/defpages (common/pages)
  [::page 
   [::new-page [] (browser click ::new)]
   [::named-page [org-name] (nav/choose-left-pane  org-name)]]
  [::nav/top-level
   [::page-via-org-switcher [] (browser click ::switcher)
    [::link-via-org-switcher [] (browser clickAndWait ::manage-switcher-link)
     [::new-page-via-org-switcher [] (browser click ::new)]]]])


;; Tasks

(defn create
  "Creates an organization with the given name and optional description."
  [name & [{:keys [description initial-env-name initial-env-description go-through-org-switcher]}]]
  (nav/go-to (if go-through-org-switcher ::new-page-via-org-switcher ::new-page))
  (sel/fill-ajax-form {::name-text name
                       ::description-text description
                       ::initial-env-name-text initial-env-name
                       ::initial-env-desc-text initial-env-description}
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
  ((->> ::active (browser getAttributes) (into {})) "title"))

(defn switch
  "Switch to the given organization in the UI. If no args are given,
   the value of *session-org* is used. If force? is true,switch even
   if the org switcher is already on the requested org. Optionally
   also select the default org for this user. To remove any default
   org for this user, set default org to :none. Using force is not
   necessary if also setting the default-org."
  ([] (switch *session-org*))
  ([org-name & [{:keys [force? default-org]}]]
     (when (or force?
               default-org
               (not= (current) org-name)) 
       (browser click ::switcher)
       (when default-org
         (let [current-default (try (browser getText ::default)
                                    (catch SeleniumException _ :none))]
           (when (not= current-default default-org)
             (browser click (default-star (if (= default-org :none)
                                            current-default
                                            default-org)))
             (notification/check-for-success))))
       (when org-name
         (browser clickAndWait (switcher-link org-name))))))

(defn before-test-switch
  "A pre-made fn to switch to the session org, meant to be used in
   test groups as a :test-setup."
  [& _]
  (switch))