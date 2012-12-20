(ns katello.activation-keys
  (:require (katello [navigation :as nav]
                     [notifications :as notification]
                     [ui-common :as common]
                     [ui :as ui])
            [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]))

;; Locators

(ui/deflocators {::new                     "new"
                 ::name-text               "activation_key[name]"
                 ::description-text        "activation_key[description]"
                 ::template-select         "activation_key[system_template_id]"
                 ::save                    "save_key"
                 ::applied-subscriptions   (ui/menu-link "applied_subscriptions")
                 ::available-subscriptions (ui/menu-link "available_subscriptions")
                 ::add-subscriptions       "//input[@id='subscription_submit_button']"            
                 ::remove-link             (ui/remove-link "activation_keys")
                 ::release-version-text    "system[releaseVer]"})

(sel/template-fns
 {subscription-checkbox "//a[.='%s']/../span/input[@type='checkbox']"
  applied-subscriptions "xpath=(//table[@class='filter_table']//a[contains(@href, 'providers') or contains(@href, 'subscriptions')])[%s]"})

;; Nav

(nav/defpages (common/pages)
  [::page
   [::named-page [activation-key-name] (nav/choose-left-pane activation-key-name)]
   [::new-page [] (browser click ::new)]])

;; Tasks

(defn create
  "Creates an activation key with the given properties. Description
  and system-template are optional."
  [{:keys [name description environment system-template] :as m}]
  (nav/go-to ::new-page)
  (browser click (ui/environment-link environment))
  (sel/fill-ajax-form {::name-text name
                       ::description-text description
                       ::template-select system-template}
                      ::save)
  (notification/check-for-success))

(defn delete
  "Deletes the given activation key."
  [name]
  (nav/go-to ::named-page {:activation-key-name name})
  (browser click ::remove-link)
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success))

(defn add-subscriptions
  "Add subscriptions to activation key."
  [name subscriptions]
  (nav/go-to ::named-page {:activation-key-name name})
  (browser click ::available-subscriptions)
  (doseq [subscription subscriptions]
    (browser click (subscription-checkbox subscription)))
  (browser click ::add-subscriptions)
  (notification/check-for-success))

(defn get-subscriptions "Get applied susbscription info from activation key"
  [name]
  (nav/go-to ::named-page {:activation-key-name name})
  (browser click ::applied-subscriptions)
  (common/extract-list applied-subscriptions))
