(ns katello.ui-common
  (:require [clojure.data.json  :as json]
            [com.redhat.qe.auto.selenium.selenium :as sel]
            [com.redhat.qe.auto.selenium.selenium
             :refer [browser]]
            (katello [navigation :as nav]
                     [tasks         :refer :all] 
                     [notifications :as notification] 
                     [conf          :refer [config]] 
                     [api-tasks     :refer [when-katello when-headpin]]) 
            [slingshot.slingshot :refer [throw+ try+]]
            [test.assert         :as assert]
            [inflections.core    :refer [pluralize]] 
            (clojure [string     :refer [capitalize replace-first]] 
                     [set        :refer [union]]
                     [string     :as string]))
  (:import [com.thoughtworks.selenium SeleniumException]
           [java.text SimpleDateFormat]))

;; Locators

(sel/template-fns
 {button-div                      "//div[contains(@class,'button') and normalize-space(.)='%s']"  
  editable                        "//div[contains(@class, 'editable') and descendant::text()[substring(normalize-space(),2)='%s']]"
  environment-link                "//div[contains(@class,'jbreadcrumb')]//a[normalize-space(.)='%s']"
  left-pane-field-list            "xpath=(//div[contains(@class,'left')]//div[contains(@class,'ellipsis') or @class='block tall'])[%s]"
  link                            "link=%s"
  search-favorite                 "//span[contains(@class,'favorite') and @title='%s']"
  slide-link                      "//li[contains(@class,'slide_link') and normalize-space(.)='%s']"
  tab                             "link=%s"
  textbox                         "xpath=//*[self::input[(@type='text' or @type='password' or @type='file') and @name='%s'] or self::textarea[@name='%<s']]"})

;; Tasks

(defn- tabs
  "Takes a list of keywords, and creates mapping eg: {:my-tab 'link=My Tab'}"
  [kws]
  (->> kws
     (map (comp tab
                capitalize-all
                #(.replace % "-" " ")
                name))
     (zipmap kws)))

(defn inactive-edit-field
  "Takes a locator for an active in-place edit field, returns the
  inactive version"
  [loc]
  (format "//div[@name='%1s']" (sel/sel-locator loc)))

(defn content-search-expand-strategy
  "Returns a locator strategy function for the expansion of the
  current row. The function returned will get any cell by index
  number."
  [current-loc n]
  (sel/template (format "%s/../ul[%s]/li[$1]" current-loc n)))

;;nav tricks


(defn toggler
  "Returns a function that returns a locator for the given on/off text
   and locator strategy. Used for clicking things like +Add/Remove for
   items in changesets or permission lists."
  [[on-text off-text] loc-strategy]
  (fn [associated-text on?]
    (loc-strategy (if on? on-text off-text) associated-text)))

(def add-remove ["+ Add" "Remove"])

(defn toggle "Toggles the item from on to off or vice versa."
  [a-toggler associated-text on?]
  (sel/browser click (a-toggler associated-text on?)))

(def common
  {::save-inplace-edit             "//button[.='Save']"
   ::confirmation-dialog           "//div[contains(@class, 'confirmation')]"
   ::confirmation-yes              "//div[contains(@class, 'confirmation')]//span[.='Yes']"
   :confirmation-no               "//div[contains(@class, 'confirmation')]//span[.='No']"
   ::search-bar                    "search"
   ::search-menu                   "//form[@id='search_form']//span[@class='arrow']"
   ::search-save-as-favorite       "search_favorite_save"
   ::search-clear-the-search       "search_clear"
   ::search-submit                 "//button[@form='search_form']"
   ;;main banner
   
   ::log-out                       "//a[normalize-space(.)='Log Out']"})

(def all-tabs
  (tabs
   (flatten
    '[:administer
      [:users
       :roles
       :manage-organizations]
      :dashboard
      :content
      [:subscriptions
       [:red-hat-subscriptions
        :activation-keys
        :import-history]
       :repositories
       [:custom-content-repositories
        :red-hat-repositories
        :package-filters
        ;; GPG Keys is defined below, because it's all caps
        ]
       :sync-management
       [:sync-status
        :sync-plans
        :sync-schedule]
       :content-search
       :system-templates
       :changeset-management
       [:changesets
        :changeset-history]]
      :systems
      [:all
       :by-environments
       :system-groups]
      

      ;;3rd level subtabs
      :create
      :details
      :registered
      :groups
      :general
      :facts
      :packages])))

(defonce ^{:doc "All the selenium locators for the Katello UI. Maps a
  keyword to the selenium locator. You can pass the keyword to
  selenium just the same as you would the locator string. See also
  SeleniumLocatable protocol."}
  uimap
  (atom (merge all-tabs common
               {
                ;;tabs with special chars in name
                :sub-organizations (tab "Sub-Organizations")})))


;; Tells the clojure selenium client where to look up keywords to get
;; real selenium locators (in uimap in this namespace).

(extend-protocol sel/SeleniumLocatable
  clojure.lang.Keyword
  (sel/sel-locator [k] (@uimap k))
  String
  (sel/sel-locator [x] x))

;;UI tasks

(defn errtype
  "Creates a predicate that matches a caught UI error of the given
   type (see known-errors). Use this predicate in a slingshot 'catch'
   statement. If any of the error types match (in case of multiple
   validation errors), the predicate will return true. Uses isa? for
   comparison, so hierarchies will be checked.
   example (try+ (dothat) (catch (errtype ::validation-error) _ nil))"
  [t]
  (with-meta
    (fn [e] (some #(isa? % t) (:types e)))
    {:type :serializable.fn/serializable-fn
     :serializable.fn/source `(errtype ~t)}))

(defn activate-in-place
  "For an in-place edit input, switch it from read-only to editing
   mode. Takes the locator of the input in editing mode as an
   argument."
  [loc]
  (browser click (inactive-edit-field loc)))

(defn in-place-edit
  "Fill out a form that uses in-place editing. Takes a map of locators
   to values. Each item will be activated, filled in and saved, and
   checks for success notification. Returns all the success
   notifications (or nil if notthing was changed)."
  [items] 
  (doall (for [[loc val] items]
           (if-not (nil? val)
             (do (activate-in-place loc)
                 (sel/fill-item loc val)
                 (browser click ::save-inplace-edit)
                 (notification/check-for-success))))))

(defn extract-list [f]
  "Extract a list of items from the UI, accepts locator function as
   argument for example, extract-left-pane-list
   left-pane-field-list"
  (let [elems (for [index (iterate inc 1)]
                (f (str index)))]
    (doall (take-while identity (for [elem elems]
                                  (try (browser getText elem)
                                       (catch SeleniumException e nil)))))))

(defn extract-left-pane-list []
  (extract-list left-pane-field-list))

(defn clear-search []
  (sel/->browser (click ::search-menu)
                 (click ::search-clear-the-search)))

(defn search
  "Search for criteria in entity-type, scope not yet implemented.
  if with-favorite is specified, criteria is ignored and the existing
  search favorite is chosen from the search menu. If add-as-favorite
  is true, use criteria and save it as a favorite, and also execute
  the search."
  [entity-type & [{:keys [criteria scope with-favorite add-as-favorite]}]]
  (nav/go-to (entity-type {:users :katello.users/page 
                           :organizations :katello.organizations/page
                           :roles :katello.roles/page
                           :subscriptions :redhat-subscriptions-page
                           :gpg-keys :katello.gpg-keys/page
                           :sync-plans :katello.sync-management/plans-page
                           :systems  :katello.systems/page
                           :system-groups :katello.system-groups/page
                           :activation-keys :katello.activation-keys/page
                           :changeset-promotion-history :katello.changesets/history-page}))
  (if with-favorite
    (sel/->browser (click ::search-menu)
                   (click (search-favorite with-favorite)))
    (do (browser type ::search-bar criteria)
        (when add-as-favorite
          (sel/->browser (click ::search-menu)
                         (click ::search-save-as-favorite)))
        (browser click ::search-submit)))
  (notification/verify-no-error {:timeout-ms 2000}))

(defn logged-in?
  "Returns true if the browser is currently showing a page where a
  user is logged in."
  []
  (browser isElementPresent ::log-out))

(defn logged-out?
  "Returns true if the login page is displayed."
  []
  (browser isElementPresent :log-in))

(defn logout
  "Logs out the current user from the UI."
  []
  (when-not (logged-out?)
    (browser clickAndWait ::log-out)))

(defn enable-redhat-repositories
  "Enable the given list of repos in the current org."
  [repos]
  (nav/go-to :redhat-repositories-page)
  (doseq [repo repos]
    (browser check (repo-enable-checkbox repo))))


(defn create-package-filter [name & [{:keys [description]}]]
  "Creates new Package Filter"
  (assert (string? name))
  (nav/go-to :new-package-filter-page)
  (sel/fill-ajax-form {:new-package-filter-name  name
                       :new-package-filter-description description}
                      :save-new-package-filter)
  (notification/check-for-success))

(defn remove-package-filter 
  "Deletes existing Package Filter"
  [package-filter-name]
  (nav/go-to :named-package-filter-page {:package-filter-name package-filter-name})
  (browser click :remove-package-filter-key )
  (browser click ::confirmation-yes)
  (notification/check-for-success))

