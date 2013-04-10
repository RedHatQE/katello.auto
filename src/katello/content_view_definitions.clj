(ns katello.content-view-definitions
  (:require [katello :as kt]
            [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            [clojure.data :as data]
            (katello [navigation :as nav]
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
   ::add-product-btn           "add_product"
   ::update-component_view     "update_component_views"
   ::remove-product            "//a[@class='remove_product']"
   ::toggle-products           "//div[@class='small_col toggle collapsed']"

   ;; Promotion
   ::publish-button            "//input[@type='button']"
   ::publish-name-text         "content_view[name]"
   ::publish-description-text  "content_view[description]"
   ::publish-new               "content_view_submit"
   ::refresh-button            "refresh_action"
   })

;; Nav
(nav/defpages (common/pages)
  [::page
   [::new-page [] (browser click ::new)]
   [::named-page [definition-name] (nav/choose-left-pane definition-name)
    [::details-page [] (browser click ::details-tab)]]])

;; Tasks

(defn create
  "Creates a new Content View Definition."
  [{:keys [name description composite composite-name org]}]
  (nav/go-to ::new-page {:org org})
  (sel/fill-ajax-form {::name-text name
                       ::description-text description
                       (fn [composite] (when composite (browser click ::composite) (browser click (composite-view-name composite-name)))) [composite]}
                      ::save-new)
  (notification/check-for-success))


(defn add-product
  "Adds the given product or repository to a content view definition"
  [{:keys [ name prod-name composite composite-name]}]
  (nav/go-to name)
  (browser getEval ::sel-products)
  (browser click ::content-tab)
  ;; Composite Content Views are made up of other published views...
  (if composite
    (do
      (sel/->browser
       (click (composite-view-name composite-name))
       (click ::update-component_view)))
    ;; Non-composite Content Views are made up of products and/or repositories.
    (do
      (sel/->browser
       (mouseUp (->  prod-name :name product-or-repository))
       (click ::add-product-btn)
       (click ::update-content))))
  (notification/check-for-success))

(defn remove-product
  "Removes the given product from existing Content View"
  [{:keys [name prod-name all-products composite composite-name]}]
  (nav/go-to name)
  (browser click ::content-tab)
  (if composite
    (do
      (sel/->browser
       (click (composite-view-name composite-name))
       (click ::update-component_view)))
    (do (if all-products
          (do
            (sel/->browser
             (click ::remove-product)
             (click ::update-content)))
          (do
            (sel/->browser
             (click ::toggle-products)
             (click (-> prod-name :name remove-repository))
             (click ::update-content))))))
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

(defn update
  "Edits an existing Content View Definition."
  [name updated]
  (nav/go-to ::details-page {:definition-name name})
  (common/in-place-edit {::details-name-text (:name updated)
                         ::details-description-text (:description updated)})
  (notification/check-for-success))

(defn delete
  "Deletes an existing View Definition."
  [name]
  (nav/go-to name)
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
           :update update}
  
  tasks/Uniqueable tasks/entity-uniqueable-impl
  nav/Destination {:go-to (fn [dn] (nav/go-to ::named-page {:definition-name dn
                                                            :org (kt/org dn)}))})

