(ns katello.ui
  (:require [com.redhat.qe.auto.selenium.selenium :as sel]))

;; Locators

(sel/template-fns
 {button-div           "//div[contains(@class,'button') and normalize-space(.)='%s']"  
  editable             "//div[contains(@class, 'editable') and descendant::text()[substring(normalize-space(),2)='%s']]"
  environment-link     "//div[contains(@class,'jbreadcrumb')]//a[normalize-space(.)='%s']"
  left-pane-field-list "xpath=(//div[contains(@class,'left')]//div[contains(@class,'ellipsis') or @class='block tall'])[%s]"
  link                 "link=%s"
  remove-link          "//a[@class='remove_item' and contains(@href,'%s')]"
  menu-link            "//*[@id='%s']/a"
  search-favorite      "//span[contains(@class,'favorite') and @title='%s']"
  slide-link           "//li[contains(@class,'slide_link') and normalize-space(.)='%s']"
  tab                  "link=%s"
  textbox              "xpath=//*[self::input[(@type='text' or @type='password' or @type='file') and @name='%s'] or self::textarea[@name='%<s']]"})




;;
;; Tells the clojure selenium client where to look up keywords to get
;; real selenium locators.
;;

(defmulti locator (comp find-ns symbol namespace))

(defmacro deflocators
  "Define locators needed in this namespace and its dependent namespaces.
   m is a map of locators.  Optionally, provide other maps containing
   locators needed in this namespace, for m to be merged with.
     Example, (deflocators {:foo 'bar'} other.ns/locators)"
  [m & others]
  `(do (def ~'locators ~m)
       (defmethod locator *ns* [k#]
         (k# (merge ~@others ~'locators)))))

(deflocators
  {::save-inplace-edit       "//div[contains(@class, 'editable')]//button[@type='submit']"
   ::confirmation-dialog     "//div[contains(@class, 'confirmation')]"

   ;; use index, no other identifiable info in the DOM
   ::confirmation-yes        "xpath=(//div[contains(@class, 'confirmation')]//span[@class='ui-button-text'])[1]" 

   ::search-bar              "search"
   ::search-menu             "//form[@id='search_form']//span[@class='arrow']"
   ::search-save-as-favorite "search_favorite_save"
   ::search-clear-the-search "search_clear"
   ::search-submit           "//button[@form='search_form']"
   ::expand-path             "path-collapsed"
   ::total-results-count     "total_results_count"
   ::current-items-count     "current_items_count"
   ::log-out                 "//div[@id='widget-container']//a[contains(@href,'logout')]"})

(extend-protocol sel/SeleniumLocatable
  clojure.lang.Keyword
  (sel/sel-locator [k] (locator k))
  String
  (sel/sel-locator [x] x))

(defn toggler
  "Returns a function that returns a locator for the given on/off text
   and locator strategy. Used for clicking things like +Add/Remove for
   items in changesets or permission lists."
  [[on-text off-text] loc-strategy]
  (fn [associated-text on?]
    (loc-strategy (if on? on-text off-text) associated-text)))

(def add-remove ["+ Add" "Remove"])

(defn- item-count [loc]
  (->> loc
     (sel/browser getText)
     Integer/parseInt))

(def current-items
  ^{:doc "Returns the number of shown left pane items according to the katello ui."}
  (partial item-count ::current-items-count))

(def total-items
  ^{:doc "Returns the number of total left pane items according to the katello ui."}
  (partial item-count ::total-results-count))
