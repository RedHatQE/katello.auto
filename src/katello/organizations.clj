(ns katello.organizations
  (:require [com.redhat.qe.auto.selenium.selenium :as sel]
            [com.redhat.qe.auto.selenium.selenium :refer [browser]]
            (katello [navigation :as nav]
                     [ui-common :as ui] 
                     [notifications :as notification]
                     [conf :refer [*session-org* with-org]]))
  (:import [com.thoughtworks.selenium SeleniumException]))

;; Locators

(sel/template-fns
 {default-org-star "//div[@id='orgbox']//a[.='%s']/../span[starts-with(@id,'favorite')]"
  org-switcher     "//div[@id='orgbox']//a[.='%s']"})

(swap! ui/uimap merge
       {:new-organization          "//a[@id='new']"
        :create-organization       "organization_submit"
        :org-name-text             "organization[name]"
        :org-description-text      "organization[description]"
        :org-environments          (ui/link "Environments")
        :edit-organization         (ui/link "Edit")
        :remove-organization       (ui/link "Remove Organization")
        :org-initial-env-name-text "environment[name]"
        :org-initial-env-desc-text "environment[description]"})

(nav/graft-page-tree
 :administer-tab
 [:manage-organizations-page [] (sel/browser clickAndWait :manage-organizations)
  [:new-organization-page [] (sel/browser click :new-organization)]
  [:named-organization-page [org-name] (nav/choose-left-pane  org-name)]])

(nav/graft-page-tree
 :top-level
 [:organizations-page-via-org-switcher [] (sel/browser click :org-switcher)
  [:organizations-link-via-org-switcher [] (sel/browser clickAndWait :manage-organizations-link)
   [:new-organization-page-via-org-switcher [] (sel/browser click :new-organization)]]])

;; Tasks

(defn create
  "Creates an organization with the given name and optional description."
  [name & [{:keys [description initial-env-name initial-env-description go-through-org-switcher]}]]
  (nav/go-to (if go-through-org-switcher :new-organization-page-via-org-switcher :new-organization-page))
  (fill-ajax-form {:org-name-text name
                   :org-description-text description
                   :org-initial-env-name-text initial-env-name
                   :org-initial-env-desc-text initial-env-description}
                  :create-organization)
  (notification/check-for-success {:match-pred (notification/request-type? :org-create)}))

(defn delete
  "Deletes the named organization."
  [org-name]
  (nav/go-to :named-organization-page {:org-name org-name})
  (browser click :remove-organization)
  (browser click :confirmation-yes)
  (notification/check-for-success
   {:match-pred (notification/request-type? :org-destroy)})   ;queueing success
  (browser refresh)
  (notification/check-for-success {:timeout-ms 180000})) ;for actual delete

(defn edit
  "Edits an organization. Currently the only property of an org that
   can be edited is the org's description."
  [org-name & {:keys [description]}]
  (nav/go-to :named-organization-page {:org-name org-name})
  (in-place-edit {:org-description-text description}))

(defn current
  "Return the currently active org (a string) shown in the org switcher."
  []
  ((->> :active-org (browser getAttributes) (into {})) "title"))

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
       (browser click :org-switcher)
       (when default-org
         (let [current-default (try (browser getText :default-org)
                                    (catch SeleniumException _ :none))]
           (when (not= current-default default-org)
             (browser click (default-org-star (if (= default-org :none)
                                                current-default
                                                default-org)))
             (notification/check-for-success))))
       (when org-name
         (browser clickAndWait (org-switcher org-name))))))

(defn before-test-switch
  "A pre-made fn to switch to the session org, meant to be used in
   test groups as a :test-setup."
  [& _]
  (switch))