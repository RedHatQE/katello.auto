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
  composite-view-name         "//td[@class='view_checkbox' and contains(., '%s')]/input"
  composite-disabled          "//td[@class='view_checkbox' and contains(., '%s')]/input@disabled"
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
   ::filter-name-text          "//input[@id='filter_name' @class='name_input']"
   ::filter-create             "//input[@class='create_button']"
   
   
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
                             (browser click (composite-view-name composite-name))))) [composite]}
                      ::save-new)
  (notification/check-for-success {:match-pred (notification/request-type? :cv-create)}))

(defn- add-repo
  "Add the given repository to content-view definition"
  [repos]
  (browser click ::content-tab)
  (doseq [repo repos]
    (sel/->browser
      (mouseUp (-> repo :name product-or-repository))
      (click ::add-product-btn)
      (click ::update-content))
    (notification/check-for-success {:match-pred (notification/request-type? :cv-update-content)})))

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
    (notification/check-for-success {:match-pred (notification/request-type? :cv-update-content)})))
  
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

(defn- edit-content-view-details [name description]
  (browser click ::details-tab)
  (common/in-place-edit {::details-name-text name
                         ::details-description-text description})
  (notification/check-for-success {:match-pred (notification/request-type? :cv-update)}))

(defn- add-to
  "Adds the given product to a content view definition"
  [products]
  (browser click ::content-tab)
  (doseq [product products]
    (sel/->browser
      (mouseUp (-> product :name product-or-repository))
      (click ::add-product-btn)
      (click ::update-content))
    (notification/check-for-success {:match-pred (notification/request-type? :cv-update-content)})))
  
(defn- remove-from
  "Removes the given product from existing Content View"
  [products]
  (browser click ::content-tab)
  (doseq [product products]
    (sel/->browser
      (mouseUp (->  product :name product-or-repository))
      (click (-> product :name remove-product))
      (click ::update-content))
    (notification/check-for-success {:match-pred (notification/request-type? :cv-update-content)})))

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
  (notification/check-for-success {:match-pred (notification/request-type? :cv-destroy)}))

(defn clone
  "Clones a content-view definition, given the name of the original definition
   to clone, and the new name and description."
  [orig clone]
  (nav/go-to orig)
  (browser click ::clone)
  (sel/fill-ajax-form {::sg/copy-name-text (:name clone)
                       ::sg/copy-description-text (:description clone)}
                      ::sg/copy-submit)
  (notification/check-for-success {:match-pred (notification/request-type? :cv-clone)}))

(extend katello.ContentView
  ui/CRUD {:create create
           :delete delete
           :update* update}
    
  tasks/Uniqueable {:uniques (fn [t] (for [ts (tasks/timestamps)]
                                       (let [stamp-fn (partial tasks/stamp ts)]
                                         (-> t
                                             (update-in [:name] stamp-fn)
                                             (update-in [:published-name] stamp-fn)))))}
  nav/Destination {:go-to (partial nav/go-to ::named-page)})

