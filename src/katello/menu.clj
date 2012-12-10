(ns katello.menu
  (:require [com.redhat.qe.auto.selenium.selenium :refer [browser] :as sel] 
            (katello [ui :as ui]
                     [navigation :as nav])))

(sel/template-fns
 {menu-link "//*[@id='%s']/a"})

;; Locators
(swap! ui/locators merge
       {::administer-link (menu-link "admin")
        ::users-link (menu-link "users")
        ::roles-link (menu-link "roles")
        ::manage-organizations-link (menu-link "orgs")
        ::dashboard-link (menu-link "dashboard")
        ::content-link (menu-link "content")
        ::subscriptions-link (menu-link "subscriptions")
        ::red-hat-subscriptions-link (menu-link "red_hat_subscriptions")
        ::activation-keys-link (menu-link "activation_keys")
        ::import-history-link (menu-link "import_history")
        ::repositories-link (menu-link "providers")
        ::custom-content-repositories-link (menu-link "custom_providers")
        ::red-hat-repositories-link (menu-link "redhat_providers")
        ::package-filters-link (menu-link "filters")
        ::sync-management-link (menu-link "sync_mgmt")
        ::sync-status-link (menu-link "sync_status")
        ::sync-plans-link (menu-link "sync_plans")
        ::sync-schedule-link (menu-link "sync_schedule")
        ::content-search-link (menu-link "content_search")
        ::system-templates-link (menu-link "system_templates")
        ::changeset-management-link (menu-link "changeset_management")
        ::changesets-link (menu-link "changesets")
        ::changeset-history-link (menu-link "changeset")
        ::systems-link (menu-link "systems")
        ::systems-all-link (menu-link "registered")
        ::by-environments-link (menu-link "env")
        ::system-groups-link (menu-link "system_groups")
        ::create-link (menu-link "")
        ::details-link (menu-link "")
        ::registered-link (menu-link "")
        ::groups-link (menu-link "")
        ::general-link (menu-link "")
        ::facts-link (menu-link "")
        ::packages-link (menu-link "")
        ::gpg-keys-link (menu-link "")})

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