(ns katello.menu
  (:require [com.redhat.qe.auto.selenium.selenium :refer [browser]] 
            (katello [ui :as ui]
                     [navigation :as nav])))

;; Locators
(swap! ui/locators merge
       {::administer-link "admin"
        ::users-link "users"
        ::roles-link "roles"
        ::manage-organizations-link "organizations"
        ::dashboard-link "dashboard"
        ::content-link "content"
        ::subscriptions-link "subscriptions"
        ::red-hat-subscriptions-link "red_hat_subscriptions"
        ::activation-keys-link "activation_keys"
        ::import-history-link "import_history"
        ::repositories-link "providers"
        ::custom-content-repositories-link "custom_providers"
        ::red-hat-repositories-link "redhat_providers"
        ::package-filters-link "filters"
        ::sync-management-link "sync_mgmt"
        ::sync-status-link "sync_status"
        ::sync-plans-link "sync_plans"
        ::sync-schedule-link "sync_schedule"
        ::content-search-link "content_search"
        ::system-templates-link "system_templates"
        ::changeset-management-link "changeset_management"
        ::changesets-link "changesets"
        ::changeset-history-link "changeset"
        ::systems-link "systems"
        ::systems-all-link "registered"
        ::by-environments-link "env"
        ::system-groups-link "system_groups"
        ::create-link ""
        ::details-link ""
        ::registered-link ""
        ::groups-link ""
        ::general-link ""
        ::facts-link ""
        ::packages-link ""})

;; Nav

(nav/add-subnavigation 
 ::nav/top-level
 
 [::systems-menu [] (browser mouseOver ::systems-link)
  [:katello.systems/page [] (browser click ::systems-all-link)]]

 [::content-menu [] (browser mouseOver ::content-link)
  [:subscriptions [] (browser mouseOver ::subscriptions-link)
   [:redhat-subscriptions-page [] (browser clickAndWait ::red-hat-subscriptions-link)]]

  [::repositories-menu [] (browser mouseOver ::repositories-link)
   [::katello.repositories/custom-providers-page [] (browser clickAndWait ::custom-content-repositories-link)]
   [::katello.repositories/redhat-page [] (browser clickAndWait ::red-hat-repositories-link)]
   [::katello.repositories/package-filters-page [] (browser clickAndWait ::package-filters-link)]]

  [::sync-management-menu [] (browser mouseOver ::sync-management-link)
   [:katello.sync-management/status-page [] (browser click ::sync-status-link)]
   [:katello.sync-management/plans-page [] (browser click ::sync-plans-link)]
   [:katello.sync-management/schedule-page [] (browser click ::sync-schedule-link)]]
  
  [::changeset-management-menu [] (browser mouseOver ::changeset-management-link)
   [:katello.changesets/page [] (browser clickAndWait ::changesets-link)]
   [:katello.changesets/history-page [] (browser clickAndWait ::changeset-history-link)]]]

 [::administer-menu [] (browser mouseOver ::administer-link)
  [:katello.users/page [] (browser clickAndWait ::users-link)]
  [:katello.organizations/page [] (browser clickAndWait ::manage-organizations-link)]])
