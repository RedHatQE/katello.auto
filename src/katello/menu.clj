(ns katello.menu
  (:require [clj-webdriver.taxi :as browser]
            [clj-webdriver.core :as clj-web]
            [webdriver :as wd]
            [katello :as kt]
            (katello [ui :as ui]
                     [navigation :refer [browser-fn] :as nav])))

(defn fmap "Passes all values of m through f." [f m]
  (into {} (for [[k v] m] [k (f v)])))

;; Locators
(def top-level-items {::administer-link "Administer"
                      ::dashboard-link  "Dashboard"
                      ::systems-link    "Systems"
                      ::content-link    "Content"})

(def menu-items {::by-environments-link          "By Environment"
                 ::changeset-management-link     "Changeset Management"
                 ::content-search-link           "Content Search"
                 ::content-view-definitions-link "Content View Definitions"
                 ::manage-organizations-link     "Organizations"
                 ::repositories-link             "Repositories"
                 ::roles-link                    "Roles"
                 ::sync-management-link          "Sync Management"
                 ::system-groups-link            "System Groups"
                 ::systems-all-link              "All"
                 ::users-link                    "Users"
                 ::setup-link                    "setup"})

(def subscriptions {::subscriptions-link "Subscriptions"})

(def flyout-items {::custom-content-repositories-link "Custom Content Repositories"
                   ::red-hat-repositories-link        "Red Hat Repositories"
                   ::gpg-keys-link                    "GPG Keys"
                   ::sync-plans-link                  "Sync Plans"
                   ::sync-schedule-link               "Sync Schedule"
                   ::sync-status-link                 "Sync Status"
                   ::changeset-history-link           "Changesets History"
                   ::changesets-link                  "Changesets"})

(def subscriptions-menu-items {::red-hat-subscriptions-link "Red Hat Subscriptions"
                               ::distributors-link          "Subscription Manager Applications"
                               ::activation-keys-link       "Activation Keys"
                               ::import-history-link        "Import History"})

(def others {::notifications-link "//span[@id='unread_notices']" })

;; Define menus for katello and headpin (in headpin, Subscriptions is a top level menu,
;; in katello it's under Content).

(ui/defelements :katello.deployment/katello []
  (merge (fmap ui/menu-link top-level-items)
         (fmap ui/menu-dropdown-link (merge menu-items subscriptions))
         (fmap ui/menu-flyout-link (merge flyout-items subscriptions-menu-items))
         others))

(ui/defelements :katello.deployment/headpin []
  (merge (fmap ui/menu-link (merge top-level-items subscriptions))
         (fmap ui/menu-dropdown-link (merge menu-items subscriptions-menu-items))
         (fmap ui/menu-flyout-link flyout-items)
         others))

;; Functions

;; Nav
(def subscriptions-menu
  [:subscriptions (browser-fn (click ::subscriptions-link))
   [:katello.subscriptions/page (browser-fn (click ::red-hat-subscriptions-link))]
   [:katello.distributors/page (browser-fn (click ::distributors-link))]
   [:katello.activation-keys/page (browser-fn (click ::activation-keys-link))]
   [:katello.subscriptions/import-history-page (browser-fn (click ::import-history-link))]])

(def systems-menu
  [::systems-menu (browser-fn (click ::systems-link))
   [:katello.systems/page (browser-fn (click ::systems-all-link))]
   [:katello.systems/by-environments-page (browser-fn (click ::by-environments-link))]
   [:katello.system-groups/page (browser-fn (click ::system-groups-link))]])

(def right-hand-menus
  (list [:katello.notices/page (browser-fn (click ::notifications-link))]

        [::administer-menu (browser-fn (click ::administer-link))
         [:katello.users/page (browser-fn (click ::users-link))]
         [:katello.roles/page (browser-fn (click ::roles-link))]
         [:katello.organizations/page (browser-fn (click ::manage-organizations-link))]]))

(nav/defpages :katello.deployment/katello katello.navigation
  (concat [::nav/top-level

           [::org-context (fn [ent] (nav/switch-org (kt/org ent)))
            systems-menu

            [::content-menu (browser-fn (click ::content-link))
             subscriptions-menu

             [::repositories-menu (browser-fn (click ::repositories-link))
              [:katello.providers/custom-page (browser-fn (click ::custom-content-repositories-link))]
              [:katello.redhat-repositories/redhat-page (browser-fn (click ::red-hat-repositories-link))]
              [:katello.gpg-keys/page (browser-fn (click ::gpg-keys-link))]]

             [::sync-management-menu (browser-fn (click ::sync-management-link))
              [:katello.sync-management/status-page (browser-fn (click ::sync-status-link))]
              [:katello.sync-management/plans-page (browser-fn (click ::sync-plans-link))]
              [:katello.sync-management/schedule-page (browser-fn (click ::sync-schedule-link))]]

             [:katello.content-view-definitions/page (browser-fn (click ::content-view-definitions-link))]

             [:katello.content-search/page (browser-fn (click ::content-search-link))]

             [::changeset-management-menu (browser-fn (click ::changeset-management-link))
              [:katello.changesets/page (browser-fn (click ::changesets-link))]
              [:katello.changesets/history-page (browser-fn (click ::changeset-history-link))]]]]]
          right-hand-menus))



(nav/defpages :katello.deployment/headpin katello.navigation
  (concat [::nav/top-level

           [::org-context (fn [ent] (nav/switch-org (kt/org ent)))
            systems-menu
            subscriptions-menu]]

          right-hand-menus))
