(ns katello.system-templates
  (:require (katello [navigation :as nav]
                     [ui-common :as ui]
                     [notifications :as notification] 
                     [tasks :refer [capitalize-all]])
            [com.redhat.qe.auto.selenium.selenium :as sel]))

;; Locators

(sel/template-fns
 {template-action            "//a[@data-name='%2$s' and .='%s']"
  template-eligible-category "//div[@id='content_tree']//div[normalize-space()='%s']"
  template-product           "//span[contains(@class, 'custom-product-sprite')]/following-sibling::span/text()[contains(.,'%s')]"})

(swap! ui/uimap merge
  {:new-template                     "new"
   :template-name-text               "system_template[name]"
   :template-description-text        "system_template[description]"
   :save-new-template                "template_save" ;;when creating
   :template-eligible-package-groups (template-eligible-category "Package Groups")
   :template-eligible-packages       (template-eligible-category "Packages")
   :template-eligible-repositories   (template-eligible-category "Repositories")
   :template-package-groups          (ui/slide-link "Package Groups")
   :template-eligible-home           "//div[@id='content_tree']//span[contains(@class,'home_img_inactive')]"
   :save-template                    "save_template"}) ;;when editing

(nav/add-subnavigation
 :content-tab
 [:system-templates-page [] (sel/browser clickAndWait :system-templates)
  [:named-system-template-page [template-name] (sel/browser click (ui/slide-link template-name))]
  [:new-system-template-page [] (sel/browser click :new-template)]])

;; Tasks

(def template-toggler (ui/toggler ui/add-remove template-action))

(defn create
  "Creates a system template with the given name and optional
  description."
  [{:keys [name description]}]
  (nav/go-to :new-system-template-page)
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
  (nav/go-to :named-system-template-page {:template-name template})
  (let [add-item (fn [item] (ui/toggle template-toggler item true))]
    (doseq [group content]
      (let [category-keyword (-> group (dissoc :product) keys first)
            category-name (-> category-keyword
                             name
                             (.replace "-" " ")
                             capitalize-all)]
        (sel/->browser
         (getEval "window.onbeforeunload = function(){};") ;circumvent popup
         (sleep 2000)
         (click (template-product (:product group)))
         (sleep 2000)
         (click (template-eligible-category category-name)))
        (doall (map add-item (group category-keyword)))
        (sel/browser click :template-eligible-home)))
    (sel/browser click :save-template)
    (notification/check-for-success)))