(ns katello.navigation
  (:require (katello [conf :as conf]
                     [ui :as ui])
            [ui.navigate :as nav]
            [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser ->browser]]))

(defn environment-breadcrumb
  "Locates a link in the environment breadcrumb UI widget. If there
  are multiple environment paths, and you wish to select Library,
  'next' is required."
  [name & [next]]
  (let [prefix "//a[normalize-space(.)='%s' and contains(@class, 'path_link')"]
    (format 
     (str prefix (if next " and ../../..//a[normalize-space(.)='%s']" "") "]")
     name next)))

(defn select-environment-widget [env & [{:keys [next-env wait]}]]
  (do (when (browser isElementPresent ::ui/expand-path)
        (browser click ::ui/expand-path))
      (browser click (environment-breadcrumb (:name env) (:name next-env)))
      (when wait (browser waitForPageToLoad))))

(defn search-here [search-term]
  (sel/fill-form {::ui/search-bar search-term}
                 ::ui/search-submit (constantly nil)))

(def ^{:doc "Returns a selenium locator for an item in a left pane
             list (by the name of the item) - truncate to 32 chars to
             match ellipsis behavior."}
  left-pane-item
  (sel/template "//div[@id='list']//div[starts-with(normalize-space(.),'%1.32s')]"))

(defn scroll-to-left-pane-item [ent]
  (while (and (< (ui/current-items) (ui/total-items))
              (not (browser isElementPresent (left-pane-item (:name ent)))))
    ;;scroll to bottom of page to load more items
    (->browser (getEval (str "window.scrollTo(0,1000000);"))
               (ajaxWait))))

(defn choose-left-pane
  "Selects an entity in the left pane. If the entity is not found, a
   search is performed and the select is attempted again."
  ([entity]
     (choose-left-pane left-pane-item entity))
  ([templ entity]
     (let [loc (templ (:name entity))]
       (try (browser click loc)
            (catch com.thoughtworks.selenium.SeleniumException se
              (do (search-here (format "\"%s\"" (:name entity)))
                  (browser click loc)))))))

(defmacro browser-fn
  "produces a function that ignores context args and passes body to
  ->browser.  To be used in navigation tree as a shortcut to produce
  functions that don't need arguments and only use the browser."
  [& body]
  `(fn [& _#] (->browser ~@body)))

(defn pages []
  "The navigation layout of the UI. Each item in the tree is
  a new page or tab, that you can drill down into from its parent
  item. Each item contains a keyword to refer to the location in the
  UI, a list of any arguments needed to navigate there (for example,
  to navigate to a provider details page, you need the name of the
  provider). Finally some code to navigate to the location from its
  parent location. Other namespaces can add their structure here."
  (nav/nav-tree
   [::top-level (fn [& _] (cond (or (not (browser isElementPresent ::ui/log-out))
                                    (browser isElementPresent ::ui/confirmation-dialog))
                                (browser open (@conf/config :server-url))

                                (browser isElementPresent ::ui/back)
                                (browser clickAndWait ::ui/back)))]))

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

(defprotocol Destination
  (go-to [dest] [dest arg] [dest start arg]
    "Navigates to a given destination (with optional starting point"))

(extend clojure.lang.Keyword
  Destination
  {:go-to (fn go-to*
            ([dest-kw start-kw arg]
               (nav/navigate start-kw dest-kw (-> dest-kw page-tree nav/page-zip) (list arg)))
            ([dest-kw arg]
               (go-to* dest-kw nil arg))
            ([dest-kw] (go-to* dest-kw nil nil)))})

(defn returns-403? [url]
  (= (clojure.string/join "\n "
                          ["403 - Permission denied You are not authorised to perform this action."
                           "Please request the required privileges from an administrator."
                           "Back"])
     (->browser (open url)
                (getText "//article[@id='maincontent']"))))

(defn current-org
  "Return the currently active org (a string) shown in the org switcher."
  []
  (let [org-text ((->> ::ui/active-org (browser getAttributes) (into {})) "title")]
    (if (empty? org-text) nil org-text)))

(defn switch-org
  "Switches to the given org. Other org-switcher functionality (eg
   setting default orgs) see katello.organizations/switch."
  ([{:keys [name]}]
     {:pre [name]}
     (go-to ::top-level)
     (when-not (= name (current-org))
       (browser fireEvent ::ui/switcher "click")
       (browser clickAndWait (ui/switcher-link name)))))
