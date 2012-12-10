(ns katello.package-filters
  (:require [com.redhat.qe.auto.selenium.selenium :as sel]
            [com.redhat.qe.auto.selenium.selenium :refer [browser]] 
            (katello [navigation :as nav]
                     [notifications :as notification] 
                     [ui :as ui])))

(swap! ui/locators merge
       {::create           (ui/link "+ New Filter")
        ::name-text        "filter[name]"
        ::description-text "filter[description]"
        ::save             "filter_submit"
        ::remove           (ui/link "Remove Filter")})

(nav/add-subnavigation
  ::page
  [::new-page [] (sel/browser click ::create)]
  [::named-page [package-filter-name] (nav/choose-left-pane  package-filter-name)])


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