(ns katello.navigation
  (:require (katello [conf :as conf]
                     [ui :as ui])
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

(defn search-here [search-term]
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
              (do (search-here item)
                  (sel/browser click loc)))))))

(defonce
  ^{:doc "The navigation layout of the UI. Each item in the tree is
  a new page or tab, that you can drill down into from its parent
  item. Each item contains a keyword to refer to the location in the
  UI, a list of any arguments needed to navigate there (for example,
  to navigate to a provider details page, you need the name of the
  provider). Finally some code to navigate to the location from its
  parent location. Other namespaces can add their structure here."}
  page-tree
  (atom
   (nav/nav-tree
    [::top-level [] (if (or (not (sel/browser isElementPresent ::ui/log-out))
                           (sel/browser isElementPresent ::ui/confirmation-dialog))
                     (sel/browser open (@conf/config :server-url)))])))

(def ^{:doc "Navigates to a named location in the UI. The first
             argument should be a keyword for the place in the page
             tree to navigate to. The 2nd optional argument is a
             mapping of keywords to strings, if any arguments are
             needed to navigate there.
             Example: (nav/go-to :katello.organizations/named-page
             {:org-name 'My org'}) See also page-tree for all the
             places that can be navigated to."
       :arglists '([location-kw & [argmap]])}
  go-to (nav/nav-fn page-tree))

(defmacro add-subnavigation
  [parent-graft-point & branches]
  `(swap! page-tree nav/add-subnav-multiple ~parent-graft-point
          (list ~@(for [branch branches]
                    `(nav/nav-tree ~branch)))))

