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
;; Navigation tree - shows all the navigation paths through the ui.
;; this data is used by the katello.tasks/navigate function to get to
;; the given page.

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
       [:redhat-subscriptions-page [] (sel/browser clickAndWait :red-hat-subscriptions)]]]
     [:administer-tab [] (sel/browser mouseOver :administer)]])))

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

(defmacro add-subnavigation
  "Convenience for other namespaces to graft their subnavigation onto the main nav tree."
  [parent-graft-point branch]
  `(swap! page-tree nav/add-subnav ~parent-graft-point (nav/nav-tree ~branch)))
