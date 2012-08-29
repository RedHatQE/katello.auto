(ns katello.organizations
  (:require [katello.locators :as locators]) 
  (:use [com.redhat.qe.auto.selenium.selenium :only [browser]]
        katello.ui-tasks
        katello.notifications))

;;
;; Organizations
;;

(defn create-organization
  "Creates an organization with the given name and optional description."
  [name & [{:keys [description initial-env-name initial-env-description go-through-org-switcher]}]]
  (navigate (if go-through-org-switcher :new-organization-page-via-org-switcher :new-organization-page))
  (fill-ajax-form {:org-name-text name
                   :org-description-text description
                   :org-initial-env-name-text initial-env-name
                   :org-initial-env-desc-text initial-env-description}
                  :create-organization)
  (check-for-success))

(defn delete-organization
  "Deletes the named organization."
  [org-name]
  (navigate :named-organization-page {:org-name org-name})
  (browser click :remove-organization)
  (browser click :confirmation-yes)
  (check-for-success) ;queueing success
  (wait-for-notification-gone)
  (check-for-success {:timeout-ms 180000 :refresh? true})) ;for actual delete

(defn edit-organization
  "Edits an organization. Currently the only property of an org that
   can be edited is the org's description."
  [org-name & {:keys [description]}]
  (navigate :named-organization-page {:org-name org-name})
  (in-place-edit {:org-description-text description}))

(defn switch-organization 
  "Switch to the given organization in the UI."
  [org-name]
  (browser click :org-switcher)
  (browser clickAndWait (locators/org-switcher org-name)))

(defn ensure-organization 
  "Switch to the given org if the UI shows we are not already there."
  [org-name]
  (when-not (-> (browser getText :org-switcher) (= org-name))
    (switch-organization org-name)))

(defn current-organization []
  "Return the currently active org (a string) shown in the org switcher."
  (browser getText :active-org))

(defmacro with-organization
  "Switch to organization org-name, then execute the code in body. Finally,
   switch back to the previous org, even if there was an error."
   [org-name & body]
  `(let [curr-org# (current-organization)]
     (try (switch-organization ~org-name)
          ~@body
          (finally (switch-organization curr-org#)))))
