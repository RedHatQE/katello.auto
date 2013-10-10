(ns katello.content-view-definitions
  (:require [katello :as kt]
            [webdriver :as browser]
            [slingshot.slingshot :refer [throw+ try+]]
            [clojure.data :as data]
            (katello [navigation :as nav]
                     [rest :as rest]
                     [notifications :as notification]
                     [system-groups :as sg]
                     [tasks :refer [when-some-let] :as tasks]
                     [ui-common :as common]
                     [ui :as ui]))
  (:import java.util.Date))

;; Locators

(browser/template-fns
 {yum-product-or-repository   "//div[@id='product_select_chzn']//li[contains(text(), '%s')]"
  puppet-repository           "//div[@id='repo_select_chzn']//li[contains(text(), '%s')]"            
  filter-link                 "//a[contains(text(), 'Filter: %s')]"
  filter-name-link            "//a[contains(text(), '%s')]"
  select-filter               "//input[@value='%s']"
  select-filter-name          "//div[@class='panel_link']/a[contains(text(), '%s')]"       
  select-rule                 "//a[contains(text(), '%s')]/../input[@type='checkbox']"
  composite-view-name         "//td[@class='view_checkbox' and contains(., '%s')]/input"
  publish-view-name           "//a[@class='tipsify separator' and contains(.,'%s')]"
  status                      "//tbody[@class='views']/tr/td/a[contains(.,'%s')]/following::td/div[@class='fl']"
  refresh-cv                  "//tbody[@class='views']/tr/td/a[contains(.,'%s')]/following::td/a[@original-title='Refresh']"
  refresh-version             "//tbody[@class='views']/tr/td/a[contains(.,'%s')]/following::tr/td[2]"
  expand-toggle               "//span[contains(text(), '%s')]/parent::div/preceding-sibling::div[contains(@class, 'small_col')]"
  remove-product              "//span[@class='text' and contains(., '%s')]//a[@class='remove_product']"
  remove-repository           "//div[@class='repo' and contains(., '%s')]/a[@class='remove_repo']"})

(ui/defelements :katello.deployment/any []
  {::new                      "new"
   ::name-text                {:name "content_view_definition[name]"}
   ::label-text               {:name "content_view_definition[label]"}
   ::description-text         {:name "content_view_definition[description]"}
   ::composite                {:name "content_view_definition[composite]"}
   ::save-new                 {:name  "commit"}
   ::remove                   (ui/link "Remove")
   ::clone                    (ui/link "Clone")

   ::views-tab                 "//li[@id='view_definition_views']/a"
   ::content-tab               "//li[@id='view_definition_content']/a"
   ::filter-tab                "//li[@id='view_definition_filter']/a"
   ::details-tab               "//li[@id='view_definition_details']/a"
   ::update-content            "update_products"

   ;; Details tab
   ::details-name-text         {:tag :input, :name "view_definition[name]"}
   ::details-description-text  {:tag :textarea, :name "view_definition[description]"}

   ;; Filters tab
   ::new-filter-button         "//input[@type='button' and @value='New Filter']"
   ::filter-name-text          "//input[@id='filter_name']"
   ::filter-create             "//input[@class='create_button']"
   ::add-rule                  "//input[@value='Add New Rule']"
   ::create-rule               "//input[@class='create_button']"
   ::rule-input                "//input[@id='rule_input']"
   ::input-puppet-author       "//input[@id='units_author']"
   ::submit-rule               "//a[@id='add_rule']"
   ::remove-button             "//input[@id='remove_button']"
   ::select-filter-type        "//select[@id='filter_rule_content_type']"
   ::select-package-version    "//select[@id='units_version_type']"
   ::version-value             "//input[@id='units_version_value']"
   ::save-version              "//a[contains(text(),'Save')]"
   ::range-value1              "//input[@id='units_version_value1']"
   ::range-value2              "//input[@id='units_version_value2']"
   ::select-errata-id          "//input[@id='errata_specify']"
   ::select-errata-date-type   "//input[@id='errata_date_type']"
   ::save-errata               "//button[@type='submit']"
   ::edit-errata-from-date     "//div[@id='from_date']"
   ::input-from-date           "//input[@name='parameter[date_range][start]']"
   ::edit-errata-to-date       "//div[@id='to_date']"
   ::input-to-date             "//input[@name='parameter[date_range][end]']"  
   ::edit-inclusion-link       "//a[contains(text(),'(Edit)')]"
   ::filter-rule-exclusion     "filter_rule_inclusion_false"
   ::errata-type               "//div[@name='parameter[errata_type]']"
   ::select-errata-label       "//select[@name='parameter[errata_type]']"
   ::repo-tab                  "//a[contains(@href, '##repos')]"
   ::rules-tab                 "//a[contains(@href,'##rules')]"
   ::close-edit-inclusion      "//a[@class='close' and @data-close='subpanel']"
   
   ::add-product-btn           "add_product"
   ::add-repo                  "//a[@class='add_repo']" 
   ::update-component_view     "update_component_views"
   ::remove-repo               "//a[@class='remove_repo']"
   ::toggle-products           "//div[@class='small_col toggle collapsed']"
   ::product-in-cv             "//div/ul/li[@class='la']"

   ;; Promotion
   ::publish-button            "//input[@type='button']"
   ::publish-name-text         {:name "content_view[name]"}
   ::publish-description-text  {:name "content_view[description]"}
   ::publish-new               "//input[@name='commit']"
   ::refresh-button            "refresh_action"
   })

;; Nav
(nav/defpages :katello.deployment/any katello.menu
  [::page
   [::new-page (fn [_] (browser/click ::new))]
   [::named-page (fn [definition-name] (nav/choose-left-pane definition-name))
    [::details-page (fn [_] (browser/click ::details-tab))]
    [::content-page (fn [_] (browser/click ::content-tab))]
    [::filter-page (fn [_] (browser/click ::filter-tab))
     [::named-filter-page (fn [ent] (->> ent kt/->Filter :name filter-name-link (browser/click)))]]
    [::views-page (fn [_] (browser/click ::views-tab))]]])


;; Tasks

(def inputformat (java.text.SimpleDateFormat. "MM/dd/yyyy"))
(def outputformat (java.text.SimpleDateFormat. "yyyy-MM-dd"))
(defn- date [d] (.format inputformat (.parse inputformat d)))
(defn msg-date [d] (.format outputformat (.parse inputformat d)))

(defn check-published-view-status
  "Function to monitor the published view status from 'Generating version' to 'Refresh' "
  [published-name & [timeout-ms]]
  (browser/loop-with-timeout (or timeout-ms (* 20 60 1000)) [current-status "Generating version:"]
                         (case current-status
                           "" current-status 
                           "Error generating version" (throw+ {:type :publish-failed
                                                               :published-name published-name})
                           (do
                             (Thread/sleep 2000)
                             (recur (browser/text  (status published-name)))))))

(defn- select-repo
  "Function to select repo based on repo-type Puppet or Yum"
  [repos]
  (doseq [repo repos]
    (if (= (:repo-type repo) "yum")
      (do
        (browser/click (-> repo :name yum-product-or-repository))
        (browser/click ::add-product-btn))
      (do
        (browser/click (-> repo :name puppet-repository))))))
  
(defn- create
  "Creates a new Content View Definition."
  [{:keys [name description composite composite-names org]}]
  (nav/go-to ::new-page org)

  (browser/quick-fill `[::name-text ~name
                  ::description-text ~description
                  ::composite ~#(browser/select-deselect % composite)
                  ~@(interleave (map (comp composite-view-name :published-name) composite-names)
                                (repeat browser/click))
                  ::save-new ~browser/click])
  (notification/success-type :cv-create))

(defn- add-repo
  "Add the given repository to content-view definition"
  [repos]
  (browser/click ::content-tab)
  (select-repo repos))

(defn- remove-repo
  "Removes the given repository from existing content-view"
  [repos]
  (browser/click ::content-tab)
  (doseq [repo repos]
    (browser/click (-> (kt/product repo) :name expand-toggle)) 
    (browser/click (-> repo :name remove-repository))))
  
(defn publish
  "Publishes a Content View Definition"
  [{:keys [content-defn published-name description]} & [timeout-ms]]
  (nav/go-to content-defn)
  (browser/click ::views-tab)
  (browser/click ::publish-button)
  (browser/quick-fill [::publish-name-text published-name
                 ::publish-description-text description
                 ::publish-new browser/click])
  (check-published-view-status published-name)  
  (notification/check-for-success {:timeout-ms (* 20 60 1000) :match-pred (notification/request-type? :cv-publish)}))


(defn add-filter
  "Create a new content filter"
  [{:keys [name]}]
  (browser/click ::filter-tab)
  (browser/click ::new-filter-button)
  (browser/input-text ::filter-name-text name)
  (browser/click ::filter-create)
  (notification/success-type :filters-create))

(defn remove-filter
  "Remove the selected filter from content-view-def"
  [{:keys [name]}]
  (browser/click ::filter-tab)
  (browser/click (select-filter name))
  (browser/click ::remove-button)
  (notification/success-type :filters-destroy))

(defn- select-exclude-filter []
  "Function to enable exclusion type filter"
  (browser/click ::edit-inclusion-link)
  (browser/click ::filter-rule-exclusion)
  (browser/click ::close-edit-inclusion))

(defn add-repo-from-filters
  "Selects repo tab under CV filters"
  [repos]
  (browser/click ::repo-tab)
  (select-repo repos))

(defn select-package-version-value
  "Select package version and set values: 
   versions are: 'All Versions' 'Only version' 'Newer Than' 'Older Than' 'Range'"
  [{:keys [version-type value1 value2]}]
  (browser/select-by-text ::select-package-version
           (case version-type
             :all           "All Versions"
             :only-version  "Only Version"
             :newer-than    "Newer Than"
             :older-than    "Older Than"
             :range         "Range"))
  (when (some #{version-type} [:only-version :newer-than :older-than])   
    (browser/input-text  ::version-value value1)
    (browser/click ::save-version))
  (when (= :range version-type)
    (browser/input-text  ::range-value1 value1)
    (browser/input-text  ::range-value2 value2)
    (browser/click ::save-version)))

(defn add-rule
  "Define inclusion or exclusion rule of type Package, Package Group and Errata"
  [cv-filter]
  (browser/click ::rules-tab)
  (browser/click ::add-rule)
  (browser/select-by-text ::select-filter-type (:type cv-filter))
  (browser/click ::create-rule)
  (when (:exclude? cv-filter)
    (select-exclude-filter)))

(defn- input-rule-items
  "Function to input rule items like: name of package, package-group or errata-id"
  [items]
  (doseq [item items]
    (browser/input-text  ::rule-input item)
    (browser/click ::submit-rule)))
  
(defn filter-items 
  "Define rule to add packages to content filter"
  [cv-filter & [{:keys [items version-type value1 value2]}]]
  (add-rule cv-filter)
  (input-rule-items items)
  (when-not (= "all" version-type)
    (select-package-version-value {:version-type version-type :value1 value1 :value2 value2}))
  (browser/click (filter-link (:name cv-filter))))

(defn add-pkg-group-rule 
  "Define rule to add package groups to content filter"
  [cv-filter {:keys [pkg-groups]}]
  (add-rule cv-filter)
  (input-rule-items pkg-groups)
  (browser/click (filter-link (:name cv-filter)))
  (notification/check-for-success))

(defn filter-errata-by-id 
  "Define rule to add errata by erratum name to content filter"
  [cv-filter erratum-names]
  (add-rule cv-filter)
  (browser/click ::select-errata-id)
  (input-rule-items erratum-names)
  (browser/click (filter-link (:name cv-filter)))
  (notification/check-for-success))

(defn filter-errata-by-date-type
  "Define rule to filter errata by date to conten t filter"
  [cv-filter & [{:keys [from-date to-date errata-type]}]]
  (add-rule cv-filter)
  (browser/click ::select-errata-date-type)
  (when from-date
    (browser/click ::edit-errata-from-date)
    (browser/input-text ::input-from-date (date from-date))
    (browser/click ::save-errata))
  (when to-date
    (browser/click ::edit-errata-to-date)
    (browser/input-text ::input-to-date (date to-date))
    (browser/click ::save-errata))
  (when errata-type
    (browser/click ::errata-type)
    (browser/select-by-text  ::select-errata-label errata-type)
    (browser/click ::save-errata))
  (browser/click (filter-link (:name cv-filter)))
  (notification/check-for-success))

(defn remove-rule
  "Remove a rule from selected filter"
  [rule-names]
  (doseq [rule-name rule-names]
    (browser/click (select-rule rule-name))
    (Thread/sleep 1000)
    (browser/click ::remove-button)
    (notification/success-type :filter-rules-destroy)))

(defn- edit-content-view-details [name description]
  (browser/click ::details-tab)
  (common/in-place-edit {::details-name-text name
                         ::details-description-text description})
  (notification/success-type :cv-update))

(defn- add-product
  "Adds the given product to a content view definition"
  [products]
  (browser/click ::content-tab)
  (doseq [product products]
    (browser/click (-> product :name yum-product-or-repository))
    (browser/click ::add-product-btn)))
  
(defn- remove-product
  "Removes the given product from existing Content View"
  [products]
  (browser/click ::content-tab)
  (doseq [product products]
    (browser/click (-> product :name expand-toggle)) 
    (browser/click (-> product :name remove-product))))

(defn- update
  "Edits an existing Content View Definition."
  [content-view updated]
  (nav/go-to content-view)
  (let [[remove add] (data/diff content-view updated)]
    (when-some-let [name (:name add)
                    description (:description add)]
                   (edit-content-view-details name description))
    (when-some-let [product-to-add (:products add)
                    product-to-rm (:products remove)]
                   (add-product product-to-add)
                   (remove-product product-to-rm))
    (when-some-let [repo-to-add (:repos add)
                    repo-to-remove (:repos remove)]
                   (add-repo repo-to-add)
                   (remove-repo repo-to-remove))))

(defn update-filter
[]
"Todo")

(defn- delete
  "Deletes an existing View Definition."
  [content-defn]
  (nav/go-to content-defn)
  (browser/click ::remove)
  (browser/click ::ui/confirmation-yes)
  (notification/success-type :cv-destroy))

(defn clone
  "Clones a content-view definition, given the name of the original definition
   to clone, and the new name and description."
  [orig clone]
  (nav/go-to orig)
  (browser/click ::clone)
  (browser/quick-fill [::sg/copy-name-text (:name clone)
                              ::sg/copy-description-text (:description clone)
                              ::sg/copy-submit browser/click])
  (notification/success-type :cv-clone))

(extend katello.ContentViewDefinition
  ui/CRUD {:create create
           :delete delete
           :update* update}
    
  tasks/Uniqueable {:uniques (fn [t] (for [ts (tasks/timestamps)]
                                       (let [stamp-fn (partial tasks/stamp ts)]
                                         (-> t
                                           (update-in [:name] stamp-fn)
                                           (update-in [:published-name] #(when %1 (stamp-fn %1)))))))}
  nav/Destination {:go-to (partial nav/go-to ::named-page)})

(extend katello.Filter
  ui/CRUD {:create add-filter
           :delete remove-filter
           :update* update-filter}
  
  tasks/Uniqueable  tasks/entity-uniqueable-impl
  
  nav/Destination {:go-to (partial nav/go-to ::filter-page)})
