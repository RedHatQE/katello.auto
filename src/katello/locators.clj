(ns katello.locators
  (:require [com.redhat.qe.auto.selenium.selenium :as sel]
            [clojure.xml :as xml]
            [clojure.zip :as zip]
            (katello [conf :refer [config]] 
                     [tasks :refer [capitalize-all]]) 
            [ui.navigate :as nav]
            [clojure.string :refer [capitalize ]])
  (:import [com.redhat.qe.auto.selenium Element]))

;;ui layer

(sel/template-fns
 { add-repository                  "//div[@id='products']//div[contains(.,'%s')]/..//div[normalize-space(.)='Add Repository' and contains(@class, 'button')]"
   button-div                      "//div[contains(@class,'button') and normalize-space(.)='%s']"
   changeset                       "//div[starts-with(@id,'changeset_') and normalize-space(.)='%s']"  
   editable                        "//div[contains(@class, 'editable') and descendant::text()[substring(normalize-space(),2)='%s']]"
   environment-link                "//div[contains(@class,'jbreadcrumb')]//a[normalize-space(.)='%s']"
   left-pane-field-list            "xpath=(//div[contains(@class,'left')]//div[contains(@class,'ellipsis') or @class='block tall'])[%s]"
   link                            "link=%s"
   product-schedule                "//div[normalize-space(.)='%s']/following-sibling::div[1]"
   provider-sync-checkbox          "//table[@id='products_table']//label[normalize-space(.)='%s']/..//input"
   provider-sync-checkbox2         "//table[@id='products_table']//tr[contains(.,'%s')]/following::label[normalize-space(.)='%s']/..//input"
   provider-sync-progress          "//tr[td/label[normalize-space(.)='%s']]/td[5]"
   repo-enable-checkbox            "//table[@id='products_table']//label[normalize-space(.)='%s']/..//input" 
   schedule                        "//div[normalize-space(.)='%s']"
   search-favorite                 "//span[contains(@class,'favorite') and @title='%s']"
   slide-link                      "//li[contains(@class,'slide_link') and normalize-space(.)='%s']"
   fetch-environments-in-org       "xpath=(//div[@id='path-selected']//a/div)[%s]"
   subscription-checkbox           "//a[.='%s']/../span/input[@type='checkbox']"
   tab                             "link=%s"
   template-action                 "//a[@data-name='%2$s' and .='%s']"
   template-eligible-category      "//div[@id='content_tree']//div[normalize-space()='%s']"
   template-product                "//span[contains(@class, 'custom-product-sprite')]/following-sibling::span/text()[contains(.,'%s')]"
   textbox                         "xpath=//*[self::input[(@type='text' or @type='password' or @type='file') and @name='%s'] or self::textarea[@name='%<s']]"
   user                            "//div[@id='list']//div[contains(@class,'column_1') and normalize-space(.)='%s']"
   })

(defn- tabs
  "Takes a list of keywords, and creates mapping eg: {:my-tab 'link=My Tab'}"
  [kws]
  (->> kws
     (map (comp tab
                capitalize-all
                #(.replace % "-" " ")
                name))
     (zipmap kws)))

;;
;;UI locators - mapping of names to selenium locator strings.
;;

(def common
  {:spinner                 "//img[contains(@src,'spinner.gif')]"
   :save-inplace-edit       "//button[.='Save']"
   :save-inplace-edit-inputbutton       "//input[@value='Save']"
   :confirmation-dialog     "//div[contains(@class, 'confirmation')]"
   :confirmation-yes        "//div[contains(@class, 'confirmation')]//span[.='Yes']"
   :confirmation-no         "//div[contains(@class, 'confirmation')]//span[.='No']"
   :search-bar              "search"
   :search-menu             "//form[@id='search_form']//span[@class='arrow']"
   :search-save-as-favorite "search_favorite_save"
   :search-clear-the-search "search_clear"
   :search-submit           "//button[@form='search_form']"
   ;;main banner
   :account             "//a[@class='header-widget' and contains(@href,'users')]"
   :log-out             "//a[normalize-space(.)='Log Out']"
   :org-switcher        "switcherButton"
   :active-org          "//*[@id='switcherButton']"
   :default-org         "//div[@id='orgbox']//input[@checked='checked' and @class='default_org']/../"
   ;;inside the org switcher
   :manage-organizations-link  "manage_orgs"
   })

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
   
;;merge all the preceeding maps together, plus a few more items.
(defonce ^{:doc "All the selenium locators for the Katello UI. Maps a
  keyword to the selenium locator. You can pass the keyword to
  selenium just the same as you would the locator string. See also
  SeleniumLocatable protocol."}
  uimap
  (atom (merge all-tabs common
               { ;; login page
                :username-text     "username"
                :password-text     "password"
                :log-in            "//input[@value='Log In' or @value='Login']"

              
                ;;tabs with special chars in name
                :sub-organizations (tab "Sub-Organizations")
                   

                ;;Sync Management subtab
                :synchronize-now   "sync_button"})))

;;Tells the clojure selenium client where to look up keywords to get
;;real selenium locators (in uimap in this namespace).
(extend-protocol sel/SeleniumLocatable
  clojure.lang.Keyword
  (sel/sel-locator [k] (@uimap k))
  String
  (sel/sel-locator [x] x))

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


(def template-toggler (toggler add-remove template-action))


(defn toggle "Toggles the item from on to off or vice versa."
  [a-toggler associated-text on?]
  (sel/browser click (a-toggler associated-text on?)))




(def tab-list '(:roles-page
                :users-page 
                :systems-all-page
                :activation-keys-page
                :systems-by-environment-page))

(def ^{:doc "Tabs that don't exist in headpin"}
  katello-only-tabs
  '(:redhat-repositories-page))

