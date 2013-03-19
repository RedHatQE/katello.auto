(ns katello.content-view-definitions
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            (katello [navigation :as nav]
                     [notifications :as notification]
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
   ::remove           (ui/link "Remove")})

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

(defn delete-content-view-definition
  "Deletes an existing View Definition."
  [name]
  (nav/go-to ::named-page {:definition-name name})
  (browser click ::remove)
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success))
