(ns katello.content-view-definitions
  (:require [katello :as kt]
            [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            [clojure.data :as data]
            (katello [navigation :as nav]
                     [rest :as rest]
                     [notifications :as notification]
                     [system-groups :as sg]
                     [tasks :refer [when-some-let] :as tasks]
                     [ui-common :as common]
                     [ui :as ui])))

;; Locators

(sel/template-fns
 {product-or-repository       "//li[contains(text(), '%s')]"
  filter-link                 "//a[contains(text(), 'Filter: %s')]"
  select-filter               "//input[@value='%s']"
  select-filter-name          "//div[@class='panel_link']/a[contains(text(), '%s')]"
  select-errata-type          "//div[contains(@data-options,'%s') and @name='parameter[errata_type]']"
  select-rule                 "//a[contains(text(), '%s')]/../input[@type='checkbox']"
  composite-view-name         "//td[@class='view_checkbox' and contains(., '%s')]/input"
  publish-view-name           "//a[@class='tipsify separator' and contains(.,'%s')]"
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
   ::remove-filter             "//input[@id='remove_button']"
   ::select-filter-type        "//select[@id='filter_rule_content_type']"
   ::select-package-version    "//select[@id='units_version_type']"
   ::version-value             "//input[@id='units_version_value']"
   ::save-version              "//a[contains(text(),'Save')]"
   ::range-value1              "//input[@id='units_version_value1']"
   ::range-value2              "//input[@id='units_version_value2']"
   ::select-errata-id          "//input[@id='errata_specify']"
   ::select-errata-date-type   "//input[@id='errata_date_type']"
   ::save-errata               "//button[@type='submit']"
   ::filter-errata-from-date   "//div[@id='from_date']"
   ::filter-errata-to-date     "//div[@id='to_date']"
   ::edit-inclusion-link       "//a[contains(text(),'(Edit)')]"
   ::filter-rule-exclusion     "filter_rule_inclusion_false"
   ::close-edit-inclusion      "//a[@class='close']"
   
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
    [::filter-page (nav/browser-fn (click ::filter-tab))]
    [::views-page (nav/browser-fn (click ::views-tab))]]])


;; Tasks

(defn- create
  "Creates a new Content View Definition."
  [{:keys [name description composite composite-names org]}]
  (nav/go-to ::new-page org)
  (sel/fill-ajax-form {::name-text name
                       ::description-text description
                       (fn [composite] 
                         (when composite 
                           (browser click ::composite)
                           (doseq [composite-name composite-names]
                             (browser click (composite-view-name (:published-name composite-name)))))) [composite]}
                      ::save-new)
  (notification/success-type :cv-create))

(defn- add-repo
  "Add the given repository to content-view definition"
  [repos]
  (browser click ::content-tab)
  (doseq [repo repos]
    (sel/->browser
      (mouseUp (-> repo :name product-or-repository))
      (click ::add-product-btn)
      (click ::update-content))
    (notification/success-type :cv-update-content)))

(defn- remove-repo
  "Removes the given repository from existing content-view"
  [repos]
  (browser click ::content-tab)
  (doseq [repo repos]
    (sel/->browser
      (mouseUp (-> repo :name product-or-repository))
      (click ::add-product-btn)
      (click  (-> repo :name remove-repository))
      (click ::update-content))
    (notification/success-type :cv-update-content)))
  
(defn publish
  "Publishes a Content View Definition"
  [{:keys [content-defn published-name description]}]
  (nav/go-to content-defn)
  (browser click ::views-tab)
  (browser click ::publish-button)
  (sel/fill-ajax-form {::publish-name-text published-name
                       ::publish-description-text description}
                      ::publish-new)
  (notification/check-for-success {:timeout-ms (* 20 60 1000) :match-pred (notification/request-type? :cv-publish)}))


(defn add-filter
  "Create a new content filter"
  [{:keys [name]}]
  (sel/->browser
    (click ::filter-tab)
    (click ::new-filter-button)
    (setText ::filter-name-text name)
    (click ::filter-create))
  (notification/success-type :filters-create))

(defn remove-filter
  "Remove the given content filter from content-view-def"
  [{:keys [name]}]
  (sel/->browser
    (click ::filter-tab)
    (click (select-filter name))
    (click ::remove-filter))
  (notification/success-type :filters-destroy))

(defn select-exclude-filter
  "Function to enable exclusion type filter"
  [exclude?]
  (when exclude?
    (browser click ::edit-inclusion-link)
    (browser click ::filter-rule-exclusion)))
    ;(browser click ::close-edit-inclusion)))

(defn select-package-version-value
  "Select package version and set values: 
   versions are: 'All Versions' 'Only version' 'Newer Than' 'Older Than' 'Range'"
  [version-type & [value1 value2]]
  (case version-type
    "all"          (browser select ::select-package-version "All Versions")
    "only-version" (browser select ::select-package-version "Only Version")
    "newer-than"   (browser select ::select-package-version "Newer Than")
    "older-than"   (browser select ::select-package-version "Older Than")
    "range"        (browser select ::select-package-version "Range"))
  (when (some #{version-type} ["only-version" "newer-than" "older-than"])   
    (browser setText ::version-value value1)
    (browser click ::save-version))
  (when (= "range" version-type)
    (browser setText ::range-value1 value1)
    (browser setText ::range-value2 value2)
    (browser click ::save-version)))


(defn add-package-rule 
  "Define rule to add packages to content filter"
  [packages &[version-type value1 value2]]
  (sel/->browser
    (click ::add-rule)
    (select ::select-filter-type "Packages")
    (click ::create-rule)
    (setText ::rule-input packages)
    (click ::submit-rule))
  (when-not (nil? version-type)
    (select-package-version-value version-type value1 value2)))
  
(defn add-pkg-group-rule 
  "Define rule to add package groups to content filter"
  [pkg-groups & [exclude?]]
  (sel/->browser
    (click ::add-rule)
    (select ::select-filter-type "Package Groups")
    (click ::create-rule))
  (when exclude?
    (browser click ::edit-inclusion-link)
    (browser click ::filter-rule-exclusion))
  (doseq [pkg-group pkg-groups]
    (browser setText ::rule-input pkg-group)
    (browser click ::submit-rule))
  (notification/check-for-success))
  
  (defn filter-errata-by-id 
  "Define rule to add errata by erratum name to content filter"
  [erratum-names]
  (sel/->browser
    (click ::add-rule)
    (select ::select-filter-type "Errata")
    (click ::create-rule)
    (click ::select-errata-id))
  (doseq [erratum-name erratum-names]
    (browser setText ::rule-input erratum-name)
    (browser click ::submit-rule))
  (notification/check-for-success))

(defn filter-errata-by-type
  "Define rule to add errata by type to content filter"
  [errata-type &[exclude?]]
  (sel/->browser
    (click ::add-rule)
    (select ::select-filter-type "Errata")   
    (click ::create-rule)
    (click ::select-errata-date-type))
    (select-exclude-filter exclude?)
    (browser select (select-errata-type errata-type))
    (browser click ::save-errata)
  (notification/check-for-success))

(defn filter-errata-by-date
   "Define rule to add errata by date to content filter"
   [from-date & [to-date]]
   (sel/->browser
     (click ::add-rule)
     (select ::select-filter-type "Errata")   
     (click ::create-rule)
     (click ::select-errata-date-type)
     (setText ::filter-errata-from-date from-date)
     (click ::submit-rule))
   (notification/check-for-success)
   (sel/->browser
     (setText ::filter-errata-to-date to-date)
     (click ::submit-rule))
   (notification/check-for-success))
    
(defn remove-rule
  "Remove a rule from selected filter"
  [cv-filter rule-name]
  (sel/->browser
    (click ::filter-tab)
    (click (select-filter-name (:name cv-filter)))
    (click (select-rule rule-name))
    (click ::remove-filter))
  (notification/success-type :filter-rules-destroy))

(defn- edit-content-view-details [name description]
  (browser click ::details-tab)
  (common/in-place-edit {::details-name-text name
                         ::details-description-text description})
  (notification/success-type :cv-update))

(defn- add-to
  "Adds the given product to a content view definition"
  [products]
  (browser click ::content-tab)
  (doseq [product products]
    (sel/->browser
      (mouseUp (-> product :name product-or-repository))
      (click ::add-product-btn)
      (click ::update-content))
    (notification/success-type :cv-update-content)))
  
(defn- remove-from
  "Removes the given product from existing Content View"
  [products]
  (browser click ::content-tab)
  (doseq [product products]
    (sel/->browser
      (mouseUp (->  product :name product-or-repository))
      (click (-> product :name remove-product))
      (click ::update-content))
    (notification/success-type :cv-update-content)))

(defn- update
  "Edits an existing Content View Definition."
  [content-view updated]
  (nav/go-to content-view)
  (let [[remove add] (data/diff content-view updated)]
    (when-some-let [{:keys [name description composite composite-name]} add]
                   (edit-content-view-details name description))
    (when-some-let [product-to-add (:products add)
                    product-to-rm (:products remove)]
                   (add-to product-to-add)
                   (remove-from product-to-rm))
    (when-some-let [repo-to-add (:repos add)
                    repo-to-remove (:repos remove)]
                   (add-repo repo-to-add)
                   (remove-repo repo-to-remove))))

(defn- delete
  "Deletes an existing View Definition."
  [content-defn]
  (nav/go-to content-defn)
  (browser click ::remove)
  (browser click ::ui/confirmation-yes)
  (notification/success-type :cv-destroy))

(defn clone
  "Clones a content-view definition, given the name of the original definition
   to clone, and the new name and description."
  [orig clone]
  (nav/go-to orig)
  (browser click ::clone)
  (sel/fill-ajax-form {::sg/copy-name-text (:name clone)
                       ::sg/copy-description-text (:description clone)}
                      ::sg/copy-submit)
  (notification/success-type :cv-clone))

(extend katello.ContentView
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
           :delete remove-filter}
  
  tasks/Uniqueable  tasks/entity-uniqueable-impl
  
  nav/Destination {:go-to (partial nav/go-to ::filter-page)})