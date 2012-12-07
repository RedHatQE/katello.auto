(ns katello.activation-keys
  (:require (katello [navigation :as nav]
                     [notifications :as notification]
                     [ui-common :as ui])
            [com.redhat.qe.auto.selenium.selenium :as sel]
            [com.redhat.qe.auto.selenium.selenium :refer [browser]]))

;; Locators

(swap! ui/uimap merge
       {::new                  "new"
        ::name-text            "activation_key[name]"
        ::description-text     "activation_key[description]"
        ::template-select      "activation_key[system_template_id]"
        ::save                 "save_key"
        ::applied-subscriptions               "//a[.='Applied Subscriptions']"
        ::available-subscriptions             "//a[.='Available Subscriptions']"
        ::add-subscriptions "//input[@id='subscription_submit_button']"            
        ::remove               (ui/link "Remove Activation Key")
        ::subscriptions             "//div[contains(@class, 'panel-content')]//a[.='Subscriptions']"
        ::release-version-text                "system[releaseVer]"})

(sel/template-fns
 {fetch-applied-subscriptions "xpath=(//table[@class='filter_table']//a[contains(@href, 'providers') or contains(@href, 'subscriptions')])[%s]"})


;; Nav

(nav/add-subnavigation
 :subscriptions-tab
 [::page [] (browser clickAndWait :activation-keys)
  [::named-page [activation-key-name]
   (nav/choose-left-pane activation-key-name)]
  [::new-page [] (browser click ::new)]]                    )

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
  (browser click ::remove)
  (browser click :confirmation-yes)
  (notification/check-for-success))

(defn add-subscriptions
  "Add subscriptions to activation key."
  [name subscriptions]
  (nav/go-to ::named-page {:activation-key-name name})
  (browser click ::available-subscriptions)
  (doseq [subscription subscriptions]
    (browser click (ui/subscription-checkbox subscription)))
  (browser click ::add-subscriptions)
  (notification/check-for-success))

(defn get-subscriptions "Get applied susbscription info from activation key"
  [name]
  (nav/go-to ::named-page {:activation-key-name name})
  (sel/browser click ::applied-subscriptions)
  (ui/extract-list fetch-applied-subscriptions))