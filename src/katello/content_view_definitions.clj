(ns katello.content-view-definitions
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            (katello [navigation :as nav]
                     [notifications :as notification]
                     [ui-common :as common]
                     [ui :as ui])))

;; Locators

(sel/template-fns
 {product-or-repository    "//li[contains(text(), '%s')]"})

(ui/deflocators
  {::new              "new"
   ::name-text        "content_view_definition[name]"
   ::label-text       "katello/content_view_definition/default_label"
   ::description-text "content_view_definition[description]"
   ::composite        "content_view_definition[composite]"
   ::save-new         "commit"
   ::remove           (ui/link "Remove")

   ::views-tab        "//li[@id='view_definition_views']/a"
   ::content-tab      "//li[@id='view_definition_content']/a"
   ::filter-tab       "//li[@id='view_definition_filter']/a"
   ::details-tab      "//li[@id='view_definition_details']/a"
   ::update-content   "update_products"

   ::sel-products     "window.$(\"#product_select_chzn\").mousedown()"
   ::add-product-btn  "add_product"})

;; Nav

(nav/defpages (common/pages)
  [::page
   [::named-page [definition-name]
    (nav/choose-left-pane definition-name)]
   [::new-page [] (browser click ::new)]])

;; Tasks

(defn create-content-view-definition
  "Creates a new Content View Definition."
  [{:keys [name description composite]}]
  (nav/go-to ::new-page)
  (sel/fill-ajax-form {::name-text name
                       ::description-text description
                       ::composite composite}
                      ::save-new)
  (notification/check-for-success))

(defn add-product-to-content-view-definition
  "Adds the given product or repository to a content view definition"
  [name prod-name]
  (nav/go-to ::named-page {:definition-name name})
  (browser getEval ::sel-products)
  (browser click ::content-tab)
  (browser mouseUp (product-or-repository prod-name))
  (browser click ::add-product-btn)
  (browser click ::update-content)
  (notification/check-for-success))

(defn delete-content-view-definition
  "Deletes an existing View Definition."
  [name]
  (nav/go-to ::named-page {:definition-name name})
  (browser click ::remove)
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success))
