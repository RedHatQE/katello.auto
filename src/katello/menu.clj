(ns katello.menu
  (:require [com.redhat.qe.auto.selenium.selenium :refer [browser] :as sel] 
            (katello [ui :as ui]
                     [navigation :as nav])))

;; Locators
(swap! ui/locators merge
       {::administer-link (ui/menu-link "admin")
        ::users-link (ui/menu-link "users")
        ::roles-link (ui/menu-link "roles")
        ::manage-organizations-link (ui/menu-link "orgs")
        ::dashboard-link (ui/menu-link "dashboard")
        ::content-link (ui/menu-link "content")
        ::subscriptions-link (ui/menu-link "subscriptions")
        ::red-hat-subscriptions-link (ui/menu-link "red_hat_subscriptions")
        ::activation-keys-link (ui/menu-link "activation_keys")
        ::import-history-link (ui/menu-link "import_history")
        ::repositories-link (ui/menu-link "providers")
        ::custom-content-repositories-link (ui/menu-link "custom_providers")
        ::red-hat-repositories-link (ui/menu-link "redhat_providers")
        ::package-filters-link (ui/menu-link "filters")
        ::sync-management-link (ui/menu-link "sync_mgmt")
        ::sync-status-link (ui/menu-link "sync_status")
        ::sync-plans-link (ui/menu-link "sync_plans")
        ::sync-schedule-link (ui/menu-link "sync_schedule")
        ::content-search-link (ui/menu-link "content_search")
        ::system-templates-link (ui/menu-link "system_templates")
        ::changeset-management-link (ui/menu-link "changeset_management")
        ::changesets-link (ui/menu-link "changesets")
        ::changeset-history-link (ui/menu-link "changeset")
        ::systems-link (ui/menu-link "systems")
        ::systems-all-link (ui/menu-link "registered")
        ::by-environments-link (ui/menu-link "env")
        ::system-groups-link (ui/menu-link "system_groups")
        ::create-link (ui/menu-link "")
        
        ::registered-link (ui/menu-link "")
        ::groups-link (ui/menu-link "")
        ::general-link (ui/menu-link "")
        ::facts-link (ui/menu-link "")
        ::packages-link (ui/menu-link "")
        ::gpg-keys-link (ui/menu-link "")})

;; Nav

;;
;; Note - when you compile this namespace, any navigation items that
;; were hanging off any of the nodes in the menu will be destroyed,
;; and you will need to re-execute whatever code adds them back
;; (usually by compiling another ns like katello.organizations.
;; This can be fixed by doing a smarter add-subnavigation to not blow
;; away existing child nodes.
;;

(nav/add-subnavigation 
 ::nav/top-level
 
 [::systems-menu [] (browser mouseOver ::systems-link)
  [:katello.systems/page [] (browser click ::systems-all-link)]
  [:katello.system-groups/page [] (browser click ::system-groups-link)]]

 [::content-menu [] (browser mouseOver ::content-link)
  [:subscriptions [] (browser mouseOver ::subscriptions-link)
   [:redhat-subscriptions-page [] (browser clickAndWait ::red-hat-subscriptions-link)]
   [:katello.activation-keys/page [] (browser clickAndWait ::activation-keys-link)]]

  [::repositories-menu [] (browser mouseOver ::repositories-link)
   [:katello.providers/custom-page [] (browser clickAndWait ::custom-content-repositories-link)]
   [:katello.repositories/redhat-page [] (browser clickAndWait ::red-hat-repositories-link)]
   [:katello.package-filters/page [] (browser clickAndWait ::package-filters-link)]
   [:katello.gpg-keys/page [] (browser clickAndWait ::gpg-keys-link)]]

  [::sync-management-menu [] (browser mouseOver ::sync-management-link)
   [:katello.sync-management/status-page [] (browser click ::sync-status-link)]
   [:katello.sync-management/plans-page [] (browser click ::sync-plans-link)]
   [:katello.sync-management/schedule-page [] (browser click ::sync-schedule-link)]]

  [:katello.content-search/page [] (browser clickAndWait ::content-search-link)]
  
  [:katello.system-templates/page [] (browser clickAndWait ::system-templates-link)]

  [::changeset-management-menu [] (browser mouseOver ::changeset-management-link)
      [:katello.changesets/page [] (browser clickAndWait ::changesets-link)]
      [:katello.changesets/history-page [] (browser clickAndWait ::changeset-history-link)]]]

 [::administer-menu [] (browser mouseOver ::administer-link)
  [:katello.users/page [] (browser clickAndWait ::users-link)]
  [:katello.roles/page [] (browser clickAndWait ::roles-link)]
  [:katello.organizations/page [] (browser clickAndWait ::manage-organizations-link)]])

nil ;; don't want to see huge value above when ns is eval'd