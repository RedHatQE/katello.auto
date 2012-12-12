(ns katello.menu
  (:require [com.redhat.qe.auto.selenium.selenium :refer [browser] :as sel] 
            (katello [ui :as ui]
                     [navigation :as nav])))

;; Locators
(swap! ui/locators merge
       {::activation-keys-link             (ui/menu-link "activation_keys")
        ::administer-link                  (ui/menu-link "admin")
        ::by-environments-link             (ui/menu-link "env")
        ::changeset-history-link           (ui/menu-link "changeset")
        ::changeset-management-link        (ui/menu-link "changeset_management")
        ::changesets-link                  (ui/menu-link "changesets")
        ::content-link                     (ui/menu-link "content")
        ::content-search-link              (ui/menu-link "content_search")
        ::custom-content-repositories-link (ui/menu-link "custom_providers")
        ::dashboard-link                   (ui/menu-link "dashboard")
        ::gpg-keys-link                    (ui/menu-link "gpg")
        ::import-history-link              (ui/menu-link "import_history")
        ::manage-organizations-link        (ui/menu-link "orgs")
        ::package-filters-link             (ui/menu-link "filters")
        ::red-hat-repositories-link        (ui/menu-link "redhat_providers")
        ::red-hat-subscriptions-link       (ui/menu-link "red_hat_subscriptions")
        ::repositories-link                (ui/menu-link "providers")
        ::roles-link                       (ui/menu-link "roles")
        ::subscriptions-link               (ui/menu-link "subscriptions")
        ::sync-management-link             (ui/menu-link "sync_mgmt")
        ::sync-plans-link                  (ui/menu-link "sync_plans")
        ::sync-schedule-link               (ui/menu-link "sync_schedule")
        ::sync-status-link                 (ui/menu-link "sync_status")
        ::system-groups-link               (ui/menu-link "system_groups")
        ::system-templates-link            (ui/menu-link "system_templates")
        ::systems-all-link                 (ui/menu-link "registered")
        ::systems-link                     (ui/menu-link "systems")
        ::users-link                       (ui/menu-link "users")})

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
  [:katello.systems/page [] (browser clickAndWait ::systems-all-link)]
  [:katello.systems/by-environments-page [] (browser clickAndWait ::by-environments-link)]
  [:katello.system-groups/page [] (browser clickAndWait ::system-groups-link)]]

 [::content-menu [] (browser mouseOver ::content-link)
  [:subscriptions [] (browser mouseOver ::subscriptions-link)
   [:katello.subscriptions/page [] (browser clickAndWait ::red-hat-subscriptions-link)]
   [:katello.activation-keys/page [] (browser clickAndWait ::activation-keys-link)]]

  [::repositories-menu [] (browser mouseOver ::repositories-link)
   [:katello.providers/custom-page [] (browser clickAndWait ::custom-content-repositories-link)]
   [:katello.repositories/redhat-page [] (browser clickAndWait ::red-hat-repositories-link)]
   [:katello.package-filters/page [] (browser clickAndWait ::package-filters-link)]
   [:katello.gpg-keys/page [] (browser clickAndWait ::gpg-keys-link)]]

  [::sync-management-menu [] (browser mouseOver ::sync-management-link)
   [:katello.sync-management/status-page [] (browser clickAndWait ::sync-status-link)]
   [:katello.sync-management/plans-page [] (browser clickAndWait ::sync-plans-link)]
   [:katello.sync-management/schedule-page [] (browser clickAndWait ::sync-schedule-link)]]

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