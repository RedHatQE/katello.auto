(ns katello.organizations
  (:require [katello.locators :as locators]
            [com.redhat.qe.auto.selenium.selenium :refer [browser]]
            (katello [ui-tasks :refer :all] 
                     [notifications :refer :all]
                     [conf :refer [*session-org* with-org]]))
  (:import [com.thoughtworks.selenium SeleniumException]))

;;
;; Organizations
;;

(defn create
  "Creates an organization with the given name and optional description."
  [name & [{:keys [description initial-env-name initial-env-description go-through-org-switcher]}]]
  (navigate (if go-through-org-switcher :new-organization-page-via-org-switcher :new-organization-page))
  (fill-ajax-form {:org-name-text name
                   :org-description-text description
                   :org-initial-env-name-text initial-env-name
                   :org-initial-env-desc-text initial-env-description}
                  :create-organization)
  (check-for-success))

(defn delete
  "Deletes the named organization."
  [org-name]
  (navigate :named-organization-page {:org-name org-name})
  (browser click :remove-organization)
  (browser click :confirmation-yes)
  (check-for-success) ;queueing success
  (wait-for-notification-gone)
  (check-for-success {:timeout-ms 180000 :refresh? true})) ;for actual delete

(defn edit
  "Edits an organization. Currently the only property of an org that
   can be edited is the org's description."
  [org-name & {:keys [description]}]
  (navigate :named-organization-page {:org-name org-name})
  (in-place-edit {:org-description-text description}))

(defn current
  "Return the currently active org (a string) shown in the org switcher."
  []
  ((->> :active-org (browser getAttributes) (into {})) "title"))

(defn switch
  "Switch to the given organization in the UI. If no args are given,
   the value of *session-org* is used. If force? is true,switch even
   if the org switcher is already on the requested org. Optionally
   also select the default org for this user. Using force is not
   necessary if also setting the default-org."
  ([] (switch *session-org*))
  ([org-name & [{:keys [force? default-org]}]]
      (when (or force?
                default-org
                (not= (current) org-name)) 
        (browser click :org-switcher)
        (when default-org
          (let [current-default (try (browser getText :default-org)
                                     (catch SeleniumException _ nil))]
            (when (not= current-default default-org)
              (browser click (locators/default-org-star default-org))
              (check-for-success))))
        (browser clickAndWait (locators/org-switcher org-name)))))


