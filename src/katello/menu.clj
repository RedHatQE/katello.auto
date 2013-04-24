(ns katello.menu
  (:require [com.redhat.qe.auto.selenium.selenium :refer [browser] :as sel] 
            (katello [ui :as ui]
                     [navigation :as nav])))

(defn fmap "Passes all values of m through f." [f m]
  (into {} (for [[k v] m] [k (f v)])))

;; Locators

(ui/deflocators
  (fmap ui/menu-link {::activation-keys-link             "activation_keys"
                      ::administer-link                  "admin"
                      ::by-environments-link             "env"
                      ::changeset-history-link           "changeset"
                      ::changeset-management-link        "changeset_management"
                      ::changesets-link                  "changesets"
                      ::content-link                     "content"
                      ::content-search-link              "content_search"
                      ::content-view-definitions-link    "content_view_definitions"
                      ::custom-content-repositories-link "custom_providers"
                      ::dashboard-link                   "dashboard"
                      ::gpg-keys-link                    "gpg"
                      ::import-history-link              "import_history"
                      ::manage-organizations-link        "orgs"
                      ::red-hat-repositories-link        "redhat_providers"
                      ::red-hat-subscriptions-link       "red_hat_subscriptions"
                      ::repositories-link                "providers"
                      ::roles-link                       "roles"
                      ::subscriptions-link               "subscriptions"
                      ::distributors-link                "distributors_list"
                      ::sync-management-link             "sync_mgmt"
                      ::sync-plans-link                  "sync_plans"
                      ::sync-schedule-link               "sync_schedule"
                      ::sync-status-link                 "sync_status"
                      ::system-groups-link               "system_groups"
                      ::systems-all-link                 "registered"
                      ::systems-link                     "systems"
                      ::users-link                       "users"
                      ::setup-link                       "setup"}))

;; Functions



;; Nav

(nav/defpages (nav/pages)
  [::nav/top-level
               
   [::org-context [org] (nav/switch-org org)
    [::systems-menu [] (browser mouseOver ::systems-link)
     [:katello.systems/page [] (browser clickAndWait ::systems-all-link)]
     [:katello.systems/by-environments-page [] (browser clickAndWait ::by-environments-link)]
     [:katello.system-groups/page [] (browser clickAndWait ::system-groups-link)]]

   [::content-menu [] (browser mouseOver ::content-link)
    [:subscriptions [] (browser mouseOver ::subscriptions-link)
     [:katello.subscriptions/page [] (browser clickAndWait ::red-hat-subscriptions-link)]
     [:katello.distributors/page [] (browser clickAndWait ::distributors-link)]
     [:katello.activation-keys/page [] (browser clickAndWait ::activation-keys-link)]
     [:katello.subscriptions/import-history-page [] (browser clickAndWait ::import-history-link)]]

     [::repositories-menu [] (browser mouseOver ::repositories-link)
      [:katello.providers/custom-page [] (browser clickAndWait ::custom-content-repositories-link)]
      [:katello.repositories/redhat-page [] (browser clickAndWait ::red-hat-repositories-link)]
      [:katello.gpg-keys/page [] (browser clickAndWait ::gpg-keys-link)]]

     [::sync-management-menu [] (browser mouseOver ::sync-management-link)
      [:katello.sync-management/status-page [] (browser clickAndWait ::sync-status-link)]
      [:katello.sync-management/plans-page [] (browser clickAndWait ::sync-plans-link)]
      [:katello.sync-management/schedule-page [] (browser clickAndWait ::sync-schedule-link)]]

    [:katello.content-view-definitions/page [] (browser clickAndWait ::content-view-definitions-link)]
    
    [:katello.content-search/page [] (browser clickAndWait ::content-search-link)]
                
    [::changeset-management-menu [] (browser mouseOver ::changeset-management-link)
      [:katello.changesets/page [] (browser clickAndWait ::changesets-link)]
      [:katello.changesets/history-page [] (browser clickAndWait ::changeset-history-link)]]]]

   [::manage-orgs-menu [] (do (browser click ::ui/switcher)
                              (browser clickAndWait ::ui/manage-orgs))
    [:katello.users/page [] (browser clickAndWait ::users-link)]
    [:katello.roles/page [] (browser clickAndWait ::roles-link)]
    [:katello.organizations/page [] (browser clickAndWait ::manage-organizations-link)]]])
