(ns katello.system-templates
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser ->browser]]
            [clojure.data :as data]
            [katello :as kt]
            (katello [navigation :as nav]
                     [ui :as ui]
                     [ui-common :as common]
                     [notifications :as notification] 
                     [tasks :refer [when-some-let] :as tasks])))

;; Locators

(sel/template-fns
 {action            "//a[@data-name='%2$s' and .='%s']"
  eligible-category "//div[@id='content_tree']//div[normalize-space()='%s']"
  product           "//span[contains(@class, 'custom-product-sprite')]/following-sibling::span/text()[contains(.,'%s')]"})

(ui/deflocators
  {::new                     "new"
   ::name-text               "system_template[name]"
   ::description-text        "system_template[description]"
   ::save-new                "template_save" ;;when creating
   ::eligible-package-groups (eligible-category "Package Groups")
   ::eligible-packages       (eligible-category "Packages")
   ::eligible-repositories   (eligible-category "Repositories")
   ::package-groups          (ui/slide-link "Package Groups")
   ::eligible-home           "//div[@id='content_tree']//span[contains(@class,'home_img_inactive')]"
   ::back-to-product         "//span[starts-with(@id, 'product_')]"
   ::save                    "save_template"}) ; when editing
  

;; Nav

(nav/defpages (common/pages)
  [::page
   [::named-page [template] (browser click (ui/slide-link (:name template)))]
   [::new-page [] (browser click ::new)]])

;; Tasks

(def template-toggler (ui/toggler ui/add-remove action))

(defn create
  "Creates a system template with the given name and optional
  description."
  [{:keys [name org description]}]
  (nav/go-to ::new-page {:org org})
  (sel/fill-ajax-form {::name-text name
                       ::description-text description}
                      ::save-new)
  (notification/check-for-success))

(defn- add-remove
  "Adds or removes content to/from a given template."
  [add? template content]
  (let [add-item #(common/toggle template-toggler (:name %) add?)
        category-loc {katello.Repository ::eligible-repositories
                      katello.Package ::eligible-repositories}]
    (doseq [[prod items] (group-by kt/product content)]
      (->browser (getEval "window.onbeforeunload = function(){};") ;circumvent popup
                 (sleep 2000)
                 (click (product (:name prod)))
                 (sleep 2000))
      (doseq [[clazz prod-class-items] (group-by class items)]
        ;;stuff
        (browser click (category-loc clazz))
        (doall (map add-item prod-class-items))
        (browser click ::back-to-product))
      (browser click ::eligible-home))))

(def add-content (partial add-remove true))
(def remove-content (partial add-remove false))

(defn update [st updated]
  (let [[removed added] (data/diff st updated)]
    (when-some-let [to-add (:content added)
                    to-rm (:content removed)]
      (nav/go-to st)
      (add-content st to-add)
      (remove-content st to-rm))))

(extend katello.Template
  ui/CRUD {:create create
           :update* update}
  nav/Destination {:go-to #(nav/go-to ::named-page {:template %1
                                                    :org (:org %1)})}
  tasks/Uniqueable tasks/entity-uniqueable-impl)
