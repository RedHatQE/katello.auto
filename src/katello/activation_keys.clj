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
                 ::content-view-select     "activation_key_content_view_id"
                 ::save                    "save_key"
                 ::system-group-select     (ui/menu-link "activation_keys_menu_system_groups")
                 ::add-sys-group-form      "//form[@id='add_group_form']/button"
                 ::add-sys-group           "//input[@id='add_groups']"
                 ::system-groups           (ui/menu-link "system_mgmt")
                 ::applied-subscriptions   (ui/menu-link "applied_subscriptions")
                 ::available-subscriptions (ui/menu-link "available_subscriptions")
                 ::add-subscriptions       "//input[@id='subscription_submit_button']"            
                 ::remove-link             (ui/remove-link "activation_keys")
                 ::release-version-text    "system[releaseVer]"})

(sel/template-fns
 {subscription-checkbox "//a[.='%s']/../span/input[@type='checkbox']"
   sysgroup-checkbox "//input[@title='%s']"
  applied-subscriptions "xpath=(//table[@class='filter_table']//a[contains(@href, 'providers') or contains(@href, 'subscriptions')])[%s]"})

;; Nav

(nav/defpages (common/pages)
  [::page
   [::named-page [activation-key-name] (nav/choose-left-pane activation-key-name)
    [::system-group-menu [] (browser mouseOver ::system-groups)
     [::system-group-page [] (browser click ::system-group-select)]]]
   [::new-page [] (browser click ::new)]])

;; Tasks

(defn create
  "Creates an activation key with the given properties. Description
  and system-template are optional."
  [{:keys [name description environment system-template content-view] :as m}]
  (nav/go-to ::new-page)
  (browser click (ui/environment-link environment))
  (sel/fill-ajax-form {::name-text name
                       ::description-text description
                       ::content-view-select content-view
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

(defn associate-system-group
  "Asscociate activation key to selected sytem group"
  [name group-name]
  (nav/go-to ::system-group-page {:activation-key-name name})
  (browser click ::add-sys-group-form)
  (browser click (sysgroup-checkbox group-name))
  (browser click ::add-sys-group))

(defn get-subscriptions "Get applied susbscription info from activation key"
  [name]
  (nav/go-to ::named-page {:activation-key-name name})
  (browser click ::applied-subscriptions)
  (common/extract-list applied-subscriptions))
