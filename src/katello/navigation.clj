(ns katello.navigation
  (:require (katello [conf :as conf]
                     [ui :as ui])
            [ui.navigate :as nav]
            [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]))

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
  (do (when (browser isElementPresent ::ui/expand-path)
        (browser click ::ui/expand-path))
      (browser click (environment-breadcrumb env-name next-env-name))
      (when wait (browser waitForPageToLoad))))

(defn search-here [search-term]
  (sel/fill-form {::ui/search-bar search-term}
                 ::ui/search-submit (constantly nil)))

(def ^{:doc "Returns a selenium locator for an item in a left pane
             list (by the name of the item) - truncate to 32 chars to
             match ellipsis behavior."}
  left-pane-item
  (sel/template "//div[@id='list']//div[starts-with(normalize-space(.),'%1.32s')]"))

(defn scroll-to-left-pane-item [item]
  (while (or (< (ui/current-items) (ui/total-items))
             (not (browser isElementPresent (left-pane-item item))))
    ;;scroll to bottom of page to load more items
    (browser getEval
             (str "window.scrollTo(0,1000000);"))))

(defn choose-left-pane
  "Selects an item in the left pane. If the item is not found, a
   search is performed and the select is attempted again."
  ([item]
     (choose-left-pane left-pane-item item))
  ([templ item]
     (let [loc (templ item)]
       (try (browser click loc)
            (catch com.thoughtworks.selenium.SeleniumException se
              (do (search-here (format "\"%s\"" item))
                  (browser click loc)))))))



(defn pages []
  "The navigation layout of the UI. Each item in the tree is
  a new page or tab, that you can drill down into from its parent
  item. Each item contains a keyword to refer to the location in the
  UI, a list of any arguments needed to navigate there (for example,
  to navigate to a provider details page, you need the name of the
  provider). Finally some code to navigate to the location from its
  parent location. Other namespaces can add their structure here."
  (nav/nav-tree
   [::top-level [] (if (or (not (browser isElementPresent ::ui/log-out))
                           (browser isElementPresent ::ui/confirmation-dialog))
                     (browser open (@conf/config :server-url)))]))

(defmulti page-tree (comp find-ns symbol namespace))

(defmethod page-tree *ns* [page] (pages))

(defmacro add-subnavigation
  [tree parent-graft-point & branches]
  `(nav/add-subnav-multiple ~tree (list ~parent-graft-point
                                        (list ~@(for [branch branches]
                                                  `(nav/nav-tree ~branch))))))

(defmacro defpages
  "Define the pages needed to navigate to in this namespace, and
   dependent namespaces.  basenav is the page tree you want to graft
   onto, branches is a list of branches you want to graft.  branch
   should be formatted as [parent-graft-point child1 child2 ...]"
   [basenav & branches]
  `(do (defn ~'pages []
         (reduce nav/add-subnav-multiple
                 ~basenav
                 (list ~@(for [[parent-graft-point# & children#] branches]
                           `(list ~parent-graft-point#
                                  (list ~@(for [child# children#]
                                            `(nav/nav-tree ~child#))))))))
       (defmethod page-tree *ns* [k#] (~'pages))))

(defn go-to [location-kw & [argmap]]
  (nav/navigate location-kw (-> location-kw page-tree nav/page-zip) argmap )) 
