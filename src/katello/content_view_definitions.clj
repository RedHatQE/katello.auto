(ns katello.content-view-definitions
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            (katello [navigation :as nav]
                     [notifications :as notification]
                     [system-groups :as sg]
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
   [::named-page [definition-name]
    (nav/choose-left-pane definition-name)]
   [::new-page [] (browser click ::new)]])

;; Tasks

(defn create-content-view-definition
  "Creates a new Content View Definition."
  [name & [{:keys [description composite composite-name]}]]
  (nav/go-to ::new-page)
  (sel/fill-ajax-form {::name-text name
                       ::description-text description
                       (fn [composite] (when composite (browser click ::composite) (browser click (composite-view-name composite-name)))) [composite]}
                      ::save-new)
  (notification/check-for-success))

(defn add-product-to-content-view
  "Adds the given product or repository to a content view definition"
  [name & [{:keys [prod-name composite composite-name]}]]
  (nav/go-to ::named-page {:definition-name name})
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
       (mouseUp (product-or-repository prod-name))
       (click ::add-product-btn)
       (click ::update-content))))
  (notification/check-for-success))

(defn remove-product-from-content-view
  "Removes the given product from existing Content View"
  [name & [{:keys [prod-name all-products composite composite-name]}]]
  (nav/go-to ::named-page {:definition-name name})
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
             (click (remove-repository prod-name))
             (click ::update-content))))))
  (notification/check-for-success))

(defn publish-content-view-definition
  "Publishes a Content View Definition"
  [name published-name & [description]]
  (nav/go-to ::named-page {:definition-name name})
  (browser click ::views-tab)
  (browser click ::publish-button)
  (sel/fill-ajax-form {::publish-name-text published-name
                  ::publish-description-text description}
                 ::publish-new)
  (notification/check-for-success {:timeout-ms (* 20 60 1000)}))

(defn edit-content-view-definition
  "Edits an existing Content View Definition."
  [name & [{:keys [new-name description]}]]
  (nav/go-to ::named-page {:definition-name name})
  (browser click ::details-tab)
  (common/in-place-edit {::details-name-text new-name
                         ::details-description-text description})
  (notification/check-for-success))

(defn delete-content-view-definition
  "Deletes an existing View Definition."
  [name]
  (nav/go-to ::named-page {:definition-name name})
  (browser click ::remove)
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success))

(defn clone
  "Clones a content-view definition, given the name of the original definition
   to clone, and the new name and description."
  [orig-name new-name & [{:keys [description]}]]
  (nav/go-to ::named-page {:definition-name orig-name})
  (browser click ::clone)
  (sel/fill-ajax-form {::sg/copy-name-text new-name
                       ::sg/copy-description-text description}
                      ::sg/copy-submit)
  (notification/check-for-success)) 
