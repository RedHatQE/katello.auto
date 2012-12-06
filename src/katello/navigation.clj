(ns katello.navigation
  (:require [katello.conf :as conf]
            [katello.ui-common :as ui]
            [ui.navigate :as nav]
            [com.redhat.qe.auto.selenium.selenium :as sel]))


(defn environment-breadcrumb
  "Locates a link in the environment breadcrumb UI widget. If there
  are multiple environment paths, and you wish to select Library,
  'next' is required."
  [name & [next]]
  (let [prefix "//a[normalize-space(.)='%s' and contains(@class, 'path_link')"]
    (format 
     (str prefix (if next " and ../../..//a[normalize-space(.)='%s']" "") "]")
     name next)))

(defn select-environment-widget [env-name & [{:keys [next-env-name wait]}]]
  (do (when (sel/browser isElementPresent :expand-path)
        (sel/browser click :expand-path))
      (sel/browser click (environment-breadcrumb env-name next-env-name))
      (when wait (sel/browser waitForPageToLoad))))

(defn search [search-term]
  (sel/fill-form {:search-bar search-term}
             :search-submit (constantly nil)))

(defn left-pane-item
  "Returns a selenium locator for an item in a left
   pane list (by the name of the item)"
  [name]
  ((sel/template "//div[@id='list']//div[starts-with(normalize-space(.),'%s')]")
   (let [l (.length name)]
     (if (> l 32)
       (.substring name 0 32) ;workaround for bz 737678
       name))))

(defn choose-left-pane
  "Selects an item in the left pane. If the item is not found, a
   search is performed and the select is attempted again. Takes an
   optional post-fn to perform afterwards."
  ([item]
     (choose-left-pane left-pane-item item))
  ([templ item]
     (let [loc (templ item)]
       (try (sel/browser click loc)
            (catch com.thoughtworks.selenium.SeleniumException se
              (do (search item)
                  (sel/browser click loc)))))))



;;
;;Navigation tree - shows all the navigation paths through the ui.
;;this data is used by the katello.tasks/navigate function to get to
;;the given page.
(defonce
  ^{:doc "The navigation layout of the UI. Each item in the tree is
  a new page or tab, that you can drill down into from its parent
  item. Each item contains a keyword to refer to the location in the
  UI, a list of any arguments needed to navigate there (for example,
  to navigate to a provider details page, you need the name of the
  provider). Finally some code to navigate to the location from its
  parent location. See also katello.tasks/navigate."}
  page-tree
  (atom
   (nav/nav-tree
    [:top-level [] (if (or (not (sel/browser isElementPresent :log-out))
                           (sel/browser isElementPresent :confirmation-dialog))
                     (sel/browser open (@conf/config :server-url)))
   
     [:content-tab [] (sel/browser mouseOver :content)
      [:subscriptions-tab [] (sel/browser mouseOver :subscriptions)
       [:redhat-subscriptions-page [] (sel/browser clickAndWait :red-hat-subscriptions)]
       [:activation-keys-page [] (sel/browser clickAndWait :activation-keys)
        [:named-activation-key-page [activation-key-name]
         (choose-left-pane  activation-key-name)]
        [:new-activation-key-page [] (sel/browser click :new-activation-key)]]]
      [:repositories-tab [] (sel/browser mouseOver :repositories)
       [:custom-content-repositories-page [] (sel/browser clickAndWait :custom-content-repositories)
        [:new-provider-page [] (sel/browser click :new-provider)]
        [:named-provider-page [provider-name] (choose-left-pane  provider-name)
         [:provider-products-repos-page [] (sel/->browser (click :products-and-repositories)
                                                          (sleep 2000))
          [:named-product-page [product-name] (sel/browser click (ui/editable product-name))]
          [:named-repo-page [product-name repo-name] (sel/browser click (ui/editable repo-name))]]
         [:provider-details-page [] (sel/browser click :details)]
         [:provider-subscriptions-page [] (sel/browser click :subscriptions)]]]
       [:redhat-repositories-page [] (sel/browser clickAndWait :red-hat-repositories)]
       [:gpg-keys-page [] (sel/browser clickAndWait :gpg-keys)
        [:new-gpg-key-page [] (sel/browser click :new-gpg-key)]
        [:named-gpgkey-page [gpg-key-name] (choose-left-pane  gpg-key-name)]]
       [:package-filters-page [] (sel/browser clickAndWait :package-filters)
        [:new-package-filter-page [] (sel/browser click :create-new-package-filter)]
        [:named-package-filter-page [package-filter-name] (choose-left-pane  package-filter-name)]]]
      [:sync-management-page [] (sel/browser mouseOver :sync-management)
       [:sync-status-page [] (sel/browser clickAndWait :sync-status)]
       [:sync-plans-page [] (sel/browser clickAndWait :sync-plans)
        [:named-sync-plan-page [sync-plan-name]
         (choose-left-pane  sync-plan-name)]
        [:new-sync-plan-page [] (sel/browser click :new-sync-plan)]]
       [:sync-schedule-page [] (sel/browser clickAndWait :sync-schedule)]]
      [:changeset-promotion-history-page [] (sel/browser clickAndWait :changeset-history)]
      [:changeset-promotions-tab [] (sel/browser mouseOver :changeset-management)
       [:changesets-page [] (sel/browser clickAndWait :changesets)
        [:named-environment-changesets-page [env-name next-env-name]
         (select-environment-widget env-name {:next-env-name next-env-name :wait true})
         [:named-changeset-page [changeset-name changeset-type]
          (do
            (if (= changeset-type "deletion") (sel/browser click :select-deletion-changeset))
            (sel/browser click (ui/changeset changeset-name)))]]]]
      [:content-search-page [] (sel/browser clickAndWait :content-search)]
      [:system-templates-page [] (sel/browser clickAndWait :system-templates)
       [:named-system-template-page [template-name] (sel/browser click (ui/slide-link template-name))]
       [:new-system-template-page [] (sel/browser click :new-template)]]]
     
     [:organizations-page-via-org-switcher [] (sel/browser click :org-switcher)
      [:organizations-link-via-org-switcher [] (sel/browser clickAndWait :manage-organizations-link)
       [:new-organization-page-via-org-switcher [] (sel/browser click :new-organization)]]]
     [:administer-tab [] (sel/browser mouseOver :administer)
      [:users-page [] (sel/browser clickAndWait :users)
       [:named-user-page [username] (choose-left-pane ui/user username)
        [:user-environments-page [] (sel/browser click :environments-subsubtab)]
        [:user-roles-permissions-page [] (sel/browser click :roles-subsubtab)]]]
      [:roles-page [] (sel/browser clickAndWait :roles)
       [:named-role-page [role-name] (choose-left-pane  role-name)
        [:named-role-users-page [] (sel/browser click :role-users)]
        [:named-role-permissions-page [] (sel/browser click :role-permissions)]]]
      [:manage-organizations-page [] (sel/browser clickAndWait :manage-organizations)
       [:new-organization-page [] (sel/browser click :new-organization)]
       [:named-organization-page [org-name] (choose-left-pane  org-name) 
        [:new-environment-page [] (sel/browser click :new-environment)]
        [:named-environment-page [env-name] (sel/browser click (ui/environment-link env-name))]]]]])))

(def ^{:doc "Navigates to a named location in the UI. The first
             argument should be a keyword for the place in the page
             tree to navigate to. The 2nd optional argument is a
             mapping of keywords to strings, if any arguments are
             needed to navigate there.
             Example: (nav/go-to :named-organization-page {:org-name
             'My org'}) See also page-tree for all the places that can
             be navigated to."
       :arglists '([location-kw & [argmap]])}
  go-to (nav/nav-fn page-tree))
