(ns katello.content-view-definitions
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            (katello [navigation :as nav]
                     [notifications :as notification]
                     [system-groups :as sg]
                     [ui-common :as common]
                     [ui :as ui])))

;; Locators

(ui/deflocators
  {::new              "new"
   ::name-text        "content_view_definition[name]"
   ::label-text       "katello/content_view_definition/default_label"
   ::description-text "content_view_definition[description]"
   ::composite        "content_view_definition[composite]"
   ::save-new         "commit"
   ::clone            (ui/link "Clone")
   ::filter           (ui/link "Filter")
   ::details          (ui/link "details")
   ::remove           (ui/link "Remove")
   
   ;;content
   ::content          "//li[@id='view_definition_content']/a"
   ::add-product      "//a[@id='add_product']"
   ::save             "//input[@id='update_products']"
   
   ;publish
   ::views            (ui/link "Views")
   ::publish          "//input[@value='Publish']"
   ::view-name        "content_view[name]"
   ::view-label       "content_view[label]"
   ::view-description "content_view[description]"
   ::product-select   "//select[@id='product_select']"
   ::submit           "content_view_submit"
   })

;; Nav

(nav/defpages (common/pages)
  [::page
   [::named-page [definition-name] (nav/choose-left-pane definition-name)
    [::views-page [] (browser click ::views)]
    [::content-page [] (browser click ::content)]
    [::filter-page [] (browser click ::filter)]
    [::details-page [] (browser click ::detail)]]
   [::new-page [] (browser click ::new)]])

(sel/template-fns  
  {select-product       "//a[@class='chzn-single']/span[contains(.,'%s')]"   
   })

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

(defn delete-content-view-definition
  "Deletes an existing View Definition."
  [name]
  (nav/go-to ::named-page {:definition-name name})
  (browser click ::remove)
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success))

(defn add-product
  "add product to an existing view definition"
  [name product-name]
  (nav/go-to ::content-page {:definition-name name})
  (browser click (select-product product-name))
  (browser click :add-product)
  (browser click ::save)
  (notification/check-for-success))

(defn publish-content-view
  [name view-name & {:keys [description]}]
  (nav/go-to ::views-page {:definition-name name})
  (browser click ::publish) 
  (sel/fill-ajax-form {::view-name view-name
                       ::view-description description}
                      ::submit)
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
