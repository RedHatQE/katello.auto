(ns katello.system-templates
  (:require (katello [ui-tasks :refer [navigate]]
                     [notifications :as notification]
                     [locators :as locators]
                     [tasks :refer [capitalize-all]])
            [com.redhat.qe.auto.selenium.selenium :as sel]))

(defn create
  "Creates a system template with the given name and optional
  description."
  [{:keys [name description]}]
  (navigate :new-system-template-page)
  (sel/fill-ajax-form {:template-name-text name
                   :template-description-text description}
                  :save-new-template)
  (notification/check-for-success))

(defn add-to
  "Adds content to a given template.  Example:
   (add-toe 'mytemplate' [{:product 'prod3'
                           :packages ['rpm1' 'rpm2']}
                          {:product 'prod6'
                           :repositories ['x86_64']}]"
  [template content]
  (navigate :named-system-template-page {:template-name template})
  (let [add-item (fn [item] (locators/toggle locators/template-toggler item true))]
    (doseq [group content]
      (let [category-keyword (-> group (dissoc :product) keys first)
            category-name (-> category-keyword
                             name
                             (.replace "-" " ")
                             capitalize-all)]
        (sel/->browser
         (getEval "window.onbeforeunload = function(){};") ;circumvent popup
         (sleep 2000)
         (click (locators/template-product (:product group)))
         (sleep 2000)
         (click (locators/template-eligible-category category-name)))
        (doall (map add-item (group category-keyword)))
        (sel/browser click :template-eligible-home)))
    (sel/browser click :save-template)
    (notification/check-for-success)))