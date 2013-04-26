(ns katello.menu
  (:require [com.redhat.qe.auto.selenium.selenium :refer [browser ->browser] :as sel]
            [katello :as kt]
            (katello [ui :as ui]
                     [navigation :refer [browser-fn] :as nav])))

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

   [::org-context (fn [ent] (nav/switch-org (kt/org ent)))
    [::systems-menu (browser-fn (mouseOver ::systems-link))
     [:katello.systems/page (browser-fn (clickAndWait ::systems-all-link))]
     [:katello.systems/by-environments-page (browser-fn (clickAndWait ::by-environments-link))]
     [:katello.system-groups/page (browser-fn (clickAndWait ::system-groups-link))]]

    [::content-menu (browser-fn (mouseOver ::content-link))
     [:subscriptions (browser-fn (mouseOver ::subscriptions-link))
      [:katello.subscriptions/page (browser-fn (clickAndWait ::red-hat-subscriptions-link))]
      [:katello.distributors/page (browser-fn (clickAndWait ::distributors-link))]
      [:katello.activation-keys/page (browser-fn (clickAndWait ::activation-keys-link))]
      [:katello.subscriptions/import-history-page (browser-fn (clickAndWait ::import-history-link))]]

     [::repositories-menu (browser-fn (mouseOver ::repositories-link))
      [:katello.providers/custom-page (browser-fn (clickAndWait ::custom-content-repositories-link))]
      [:katello.repositories/redhat-page (browser-fn (clickAndWait ::red-hat-repositories-link))]
      [:katello.gpg-keys/page (browser-fn (clickAndWait ::gpg-keys-link))]]

     [::sync-management-menu (browser-fn (mouseOver ::sync-management-link))
      [:katello.sync-management/status-page (browser-fn (clickAndWait ::sync-status-link))]
      [:katello.sync-management/plans-page (browser-fn (clickAndWait ::sync-plans-link))]
      [:katello.sync-management/schedule-page (browser-fn (clickAndWait ::sync-schedule-link))]]

     [:katello.content-view-definitions/page (browser-fn (clickAndWait ::content-view-definitions-link))]

     [:katello.content-search/page (browser-fn (clickAndWait ::content-search-link))]

     [::changeset-management-menu (browser-fn (mouseOver ::changeset-management-link))
      [:katello.changesets/page (browser-fn (clickAndWait ::changesets-link))]
      [:katello.changesets/history-page (browser-fn (clickAndWait ::changeset-history-link))]]]]

   [::manage-orgs-menu (browser-fn (click ::ui/switcher)
                                    (clickAndWait ::ui/manage-orgs))
    [:katello.users/page (browser-fn (clickAndWait ::users-link))]
    [:katello.roles/page (browser-fn (clickAndWait ::roles-link))]
    [:katello.organizations/page (browser-fn (clickAndWait ::manage-organizations-link))]]])
