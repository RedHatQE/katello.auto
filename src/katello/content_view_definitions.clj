(ns katello.content-view-definitions
  (:require [katello :as kt]
            [clj-webdriver.taxi :as browser]
            [webdriver :as wd]
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

(wd/template-fns
 {product-or-repository       "//li[contains(text(), '%s')]"
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
  remove-product              "//span[@class='text' and contains(., '%s')]//a[@class='remove_product']"
  remove-repository           "//div[@class='repo' and contains(., '%s')]/a[@class='remove_repo']"})

(ui/defelements :katello.deployment/any []
  {::new                      "new"
   ::name-text                "content_view_definition[name]"
   ::label-text               "katello/content_view_definition/default_label"
   ::description-text         "content_view_definition[description]"
   ::composite                "content_view_definition[composite]"
   ::save-new                 "commit"
   ::remove                   (ui/link "Remove")
   ::clone                    (ui/link "Clone")

   ::views-tab                 "//li[@id='view_definition_views']/a"
   ::content-tab               "//li[@id='view_definition_content']/a"
   ::filter-tab                "//li[@id='view_definition_filter']/a"
   ::details-tab               "//li[@id='view_definition_details']/a"
   ::update-content            "update_products"

   ;; Details tab
   ::details-name-text         "view_definition[name]"
   ::details-description-text  "view_definition[description]"

   ;; Filters tab
   ::new-filter-button         "//input[@type='button' and @value='New Filter']"
   ::filter-name-text          "//input[@id='filter_name']"
   ::filter-create             "//input[@class='create_button']"
   ::add-rule                  "//input[@value='Add New Rule']"
   ::create-rule               "//input[@class='create_button']"
   ::rule-input                "//input[@id='rule_input']"
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
   ::close-edit-inclusion      "xpath=(//a[contains(text(),'Close')])[2]"
   
   ::sel-products              "window.$(\"#product_select_chzn\").mousedown()"
   ::sel-repo                  "//div/input[@class='product_radio' and @value='sel']"
   ::add-product-btn           "add_product"
   ::add-repo                  "//a[@class='add_repo']" 
   ::update-component_view     "update_component_views"
   ::remove-repo               "//a[@class='remove_repo']"
   ::toggle-products           "//div[@class='small_col toggle collapsed']"
   ::product-in-cv             "//div/ul/li[@class='la']"

   ;; Promotion
   ::publish-button            "//input[@type='button']"
   ::publish-name-text         "content_view[name]"
   ::publish-description-text  "content_view[description]"
   ::publish-new               "commit"
   ::refresh-button            "refresh_action"
   })

;; Nav
(nav/defpages :katello.deployment/any katello.menu
  [::page
   [::new-page (nav/browser-fn (click ::new))]
   [::named-page (fn [definition-name] (nav/choose-left-pane definition-name))
    [::details-page (nav/browser-fn (click ::details-tab))]
    [::content-page (nav/browser-fn (click ::content-tab))]
    [::filter-page (nav/browser-fn (click ::filter-tab))
     [::named-filter-page (fn [ent] (->> ent kt/->Filter :name filter-name-link (wd/click)))]]
    [::views-page (nav/browser-fn (click ::views-tab))]]])


;; Tasks

(def inputformat (java.text.SimpleDateFormat. "MM/dd/yyyy"))
(def outputformat (java.text.SimpleDateFormat. "yyyy-MM-dd"))
(defn- date [d] (.format inputformat (.parse inputformat d)))
(defn msg-date [d] (.format outputformat (.parse inputformat d)))

(defn check-published-view-status
  "Function to monitor the published view status from 'Generating version' to 'Refresh' "
  [published-name & [timeout-ms]]
  (wd/loop-with-timeout (or timeout-ms (* 20 60 1000)) [current-status "Generating version:"]
                         (case current-status
                           "" current-status 
                           "Error generating version" (throw+ {:type :publish-failed
                                                               :published-name published-name})
                           (do
                             (Thread/sleep 2000)
                             (recur (browser/text  (status published-name)))))))

(defn- create
  "Creates a new Content View Definition."
  [{:keys [name description composite composite-names org]}]
  (nav/go-to ::new-page org)
  (browser/quick-fill-submit {::name-text name}
                             {::description-text description})
  (when composite 
    (wd/click ::composite)
    (doseq [composite-name composite-names]
      (wd/click (composite-view-name (:published-name composite-name)))))
  (wd/click ::save-new) 
  (notification/success-type :cv-create))

(defn- add-repo
  "Add the given repository to content-view definition"
  [repos]
  (wd/click ::content-tab)
  (doseq [repo repos]
    (wd/move-to (-> repo :name product-or-repository))
    (wd/->browser
     (click ::add-product-btn)
     (click ::update-content))
    (notification/success-type :cv-update-content)))

(defn- remove-repo
  "Removes the given repository from existing content-view"
  [repos]
  (wd/click ::content-tab)
  (doseq [repo repos]
    (wd/move-to (-> repo :name product-or-repository))
    (wd/->browser
     (click ::add-product-btn)
     (click  (-> repo :name remove-repository))
     (click ::update-content))
    (notification/success-type :cv-update-content)))
  
(defn publish
  "Publishes a Content View Definition"
  [{:keys [content-defn published-name description]} & [timeout-ms]]
  (nav/go-to content-defn)
  (wd/click ::views-tab)
  (wd/click ::publish-button)
  (browser/quick-fill-submit {::publish-name-text published-name}
                             {::publish-description-text description}
                             {::publish-new wd/click})
  (check-published-view-status published-name)  
  (notification/check-for-success {:timeout-ms (* 20 60 1000) :match-pred (notification/request-type? :cv-publish)}))


(defn add-filter
  "Create a new content filter"
  [{:keys [name]}]
  (wd/->browser
    (click ::filter-tab)
    (click ::new-filter-button)
    (input-text ::filter-name-text name)
    (click ::filter-create))
  (notification/success-type :filters-create))

(defn remove-filter
  "Remove the selected filter from content-view-def"
  [{:keys [name]}]
  (wd/->browser
    (click ::filter-tab)
    (click (select-filter name))
    (click ::remove-button))
  (notification/success-type :filters-destroy))

(defn- select-exclude-filter []
  "Function to enable exclusion type filter"
  (wd/click ::edit-inclusion-link)
  (wd/click ::filter-rule-exclusion)
  (wd/click ::close-edit-inclusion))

(defn add-repo-from-filters
  "Selects repo tab under CV filters"
  [repos]
  (wd/click ::repo-tab)
  (doseq [repo repos]
    (wd/move-to (-> repo :name product-or-repository))
    (wd/click ::add-product-btn)
    (wd/click ::update-content)))

(defn select-package-version-value
  "Select package version and set values: 
   versions are: 'All Versions' 'Only version' 'Newer Than' 'Older Than' 'Range'"
  [{:keys [version-type value1 value2]}]
  (wd/select-by-text ::select-package-version
           (case version-type
             :all           "All Versions"
             :only-version  "Only Version"
             :newer-than    "Newer Than"
             :older-than    "Older Than"
             :range         "Range"))
  (when (some #{version-type} [:only-version :newer-than :older-than])   
    (wd/input-text  ::version-value value1)
    (wd/click ::save-version))
  (when (= :range version-type)
    (wd/input-text  ::range-value1 value1)
    (wd/input-text  ::range-value2 value2)
    (wd/click ::save-version)))

(defn- add-rule
  "Define inclusion or exclusion rule of type Package, Package Group and Errata"
  [cv-filter]
  (wd/->browser
    (click ::add-rule)
    (select ::select-filter-type (:type cv-filter))
    (click ::create-rule))
  (when (:exclude? cv-filter)
    (select-exclude-filter)))

(defn- input-rule-items
  "Function to input rule items like: name of package, package-group or errata-id"
  [items]
  (doseq [item items]
    (wd/input-text  ::rule-input item)
    (wd/click ::submit-rule)))
  
(defn add-package-rule 
  "Define rule to add packages to content filter"
  [cv-filter & [{:keys [packages version-type value1 value2]}]]
  (add-rule cv-filter)
  (input-rule-items packages)
  (when-not (= "all" version-type)
    (select-package-version-value {:version-type version-type :value1 value1 :value2 value2}))
  (wd/click (filter-link (:name cv-filter))))

(defn add-pkg-group-rule 
  "Define rule to add package groups to content filter"
  [cv-filter {:keys [pkg-groups]}]
  (add-rule cv-filter)
  (input-rule-items pkg-groups)
  (wd/click (filter-link (:name cv-filter)))
  (notification/check-for-success))

(defn filter-errata-by-id 
  "Define rule to add errata by erratum name to content filter"
  [cv-filter erratum-names]
  (add-rule cv-filter)
  (wd/click ::select-errata-id)
  (input-rule-items erratum-names)
  (wd/click (filter-link (:name cv-filter)))
  (notification/check-for-success))

(defn filter-errata-by-date-type
  "Define rule to filter errata by date to conten t filter"
  [cv-filter & [{:keys [from-date to-date errata-type]}]]
  (add-rule cv-filter)
  (wd/click ::select-errata-date-type)
  (when from-date
    (wd/click ::edit-errata-from-date)
    (wd/input-text ::input-from-date (date from-date))
    (wd/click ::save-errata))
  (when to-date
    (wd/click ::edit-errata-to-date)
    (wd/input-text ::input-to-date (date to-date))
    (wd/click ::save-errata))
  (when errata-type
    (wd/click ::errata-type)
    (wd/select-by-text  ::select-errata-label errata-type)
    (wd/click ::save-errata))
  (wd/click (filter-link (:name cv-filter)))
  (notification/check-for-success))

(defn remove-rule
  "Remove a rule from selected filter"
  [rule-names]
  (doseq [rule-name rule-names]
    (wd/click (select-rule rule-name))
    (Thread/sleep 1000)
    (wd/click ::remove-button)
    (notification/success-type :filter-rules-destroy)))

(defn- edit-content-view-details [name description]
  (wd/click ::details-tab)
  (common/in-place-edit {::details-name-text name
                         ::details-description-text description})
  (notification/success-type :cv-update))

(defn- add-to
  "Adds the given product to a content view definition"
  [products]
  (wd/click ::content-tab)
  (doseq [product products]
    (wd/move-to (-> product :name product-or-repository))
    (wd/->browser
     (click ::add-product-btn)
     (click ::update-content))
    (notification/success-type :cv-update-content)))
  
(defn- remove-from
  "Removes the given product from existing Content View"
  [products]
  (wd/click ::content-tab)
  (doseq [product products]
    (wd/move-to (->  product :name product-or-repository))
    (wd/->browser
     (click (-> product :name remove-product))
      (click ::update-content))
    (notification/success-type :cv-update-content)))

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
                   (add-to product-to-add)
                   (remove-from product-to-rm))
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
  (wd/click ::remove)
  (wd/click ::ui/confirmation-yes)
  (notification/success-type :cv-destroy))

(defn clone
  "Clones a content-view definition, given the name of the original definition
   to clone, and the new name and description."
  [orig clone]
  (nav/go-to orig)
  (wd/click ::clone)
  (browser/quick-fill-submit {::sg/copy-name-text (:name clone)}
                             {::sg/copy-description-text (:description clone)}
                             {::sg/copy-submit wd/click})
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
