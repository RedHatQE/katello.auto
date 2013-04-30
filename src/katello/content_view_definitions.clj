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
  remove-repository           "//div[@class='repo' and contains(., '%s')]/a"})

(ui/deflocators
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


   ::sel-products              "window.$(\"#product_select_chzn\").mousedown()"
   ::sel-repo                  "//div/input[@class='product_radio' and @value='sel']"
   ::add-product-btn           "add_product"
   ::add-repo                  "//a[@class='add_repo']" 
   ::update-component_view     "update_component_views"
   ::remove-product            "//a[@class='remove_product']"
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
(nav/defpages (common/pages)
  [::page
   [::new-page (nav/browser-fn (click ::new))]
   [::named-page (fn [definition-name] (nav/choose-left-pane definition-name))
    [::details-page (nav/browser-fn (click ::details-tab))]
    [::content-page (nav/browser-fn (click ::content-tab))]
    [::filter-page (nav/browser-fn (click ::filter-tab))]
    [::views-page (nav/browser-fn (click ::views-tab))]]])


;; Tasks

(defn create
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
  (notification/check-for-success))

(defn remove-repo
  "Removes the given product from existing Content View"
  [content-defn]
  (nav/go-to content-defn)
  (browser click ::content-tab)
  (sel/->browser
    (click ::toggle-products)
    (click ::sel-repo) 
    (click ::add-repo)
    (click ::remove-repo)
    (click ::update-content))
  (notification/check-for-success))
  
(defn publish
  "Publishes a Content View Definition"
  [{:keys [name published-name description]}]
  (nav/go-to name)
  (browser click ::views-tab)
  (browser click ::publish-button)
  (sel/fill-ajax-form {::publish-name-text published-name
                       ::publish-description-text description}
                      ::publish-new)
  (notification/check-for-success {:timeout-ms (* 20 60 1000)}))

(defn- edit-content-view-details [name description]
  (browser click ::details-tab)
  (common/in-place-edit {::details-name-text name
                         ::details-description-text description}))

(defn- add-to
  "Adds the given product to a content view definition"
  [products]
  (browser click ::content-tab)
  (doseq [product products]
    (sel/->browser
      (mouseUp (-> product :name product-or-repository))
      (click ::add-product-btn)
      (click ::update-content))
    (notification/check-for-success)))
  
(defn- remove-from
  "Removes the given product from existing Content View"
  [products]
  (browser click ::content-tab)
  (doseq [product products]
    (sel/->browser
      (mouseUp (->  product :name product-or-repository))
      (click ::remove-product)
      (click ::update-content))
    (notification/check-for-success)))

(defn update
  "Edits an existing Content View Definition."
  [content-view updated]
  (nav/go-to content-view)
  (let [[remove add] (data/diff content-view updated)]
    (when-some-let [{:keys [name description composite composite-name]} add]
                   (edit-content-view-details name description))
    (when-some-let [product-to-add (:products add)
                    product-to-rm (:products remove)]
                   (add-to product-to-add)
                   (remove-from product-to-rm))))

(defn delete
  "Deletes an existing View Definition."
  [content-defn]
  (nav/go-to content-defn)
  (browser click ::remove)
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success))

(defn clone
  "Clones a content-view definition, given the name of the original definition
   to clone, and the new name and description."
  [orig clone]
  (nav/go-to orig)
  (browser click ::clone)
  (sel/fill-ajax-form {::sg/copy-name-text (:name clone)
                       ::sg/copy-description-text (:description clone)}
                      ::sg/copy-submit)
  (notification/check-for-success))

(extend katello.ContentView
  ui/CRUD {:create create
           :delete delete
           :update* update}
    
  tasks/Uniqueable tasks/entity-uniqueable-impl
  nav/Destination {:go-to (partial nav/go-to ::named-page)})

