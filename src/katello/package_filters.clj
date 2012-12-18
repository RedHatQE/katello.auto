(ns katello.package-filters
  (:require [com.redhat.qe.auto.selenium.selenium :as sel]
            [com.redhat.qe.auto.selenium.selenium :refer [browser]] 
            (katello [navigation :as nav]
                     [ui-common :as common]
                     [notifications :as notification] 
                     [ui :as ui]))
  (:refer-clojure :exclude [remove]))

;; Locators

(ui/deflocators
  {::create           (ui/link "+ New Filter")
   ::name-text        "filter[name]"
   ::description-text "filter[description]"
   ::save             "filter_submit"
   ::remove           (ui/link "Remove Filter")}
  ui/locators)

;; Nav

(nav/defpages (common/pages)
  [::page
   [::new-page [] (browser click ::create)]
   [::named-page [package-filter-name] (nav/choose-left-pane  package-filter-name)]])

;; Tasks

(defn create "Creates new Package Filter"
  [name & [{:keys [description]}]]
  (assert (string? name))
  (nav/go-to ::new-page)
  (sel/fill-ajax-form {::name-text  name
                       ::description-text description}
                      ::save)
  (notification/check-for-success))

(defn remove "Deletes existing Package Filter"
  [package-filter-name]
  (nav/go-to ::named-page {:package-filter-name package-filter-name})
  (browser click ::remove )
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success))
