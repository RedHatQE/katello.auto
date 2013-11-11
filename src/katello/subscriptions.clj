(ns katello.subscriptions
  (:require (katello [ui :as ui]                    
                     [navigation :as nav])
            [webdriver :as browser]))

;; Locators

(ui/defelements :katello.deployment/any []
  {::new                      "new"
   ::upload-manifest          "upload_form_button"
   ::refresh-manifest         "refresh_form_button"
   ::delete-manifest          "delete_form_button"
   ::create                   "commit"
   ::repository-url-text      {:tag :input, :name "provider[repository_url]"}
   ::fetch-all-history        {:xpath "//div[@id='subscription_history']//td[contains(., 'imported') or contains(., 'deleted') or contains(., 'Archive') or contains(., 'Owner')]"}
   ::choose-file              "provider_contents"
   ::manifest-history         "//a[contains(@href,'history_items')]"
   ::manifest-link            "//fieldset/div/a[contains(@href,'access')]"
   ::redhat-login             "//label[@for='username' and contains(.,'Red Hat Login')]"})

(browser/template-fns
 {subs-exists       "//div[@class='one-line-ellipsis' and contains(.,'%s')]"})

;; Nav

(nav/defpages :katello.deployment/any katello.menu
  [::page
   [::new-page (fn [_] (browser/click ::new))
    [::manifest-details-page (fn [_] (browser/click ::manifest-details))]
    [::manifest-import-page (fn [_] (browser/click ::manifest-import))]
    [::manifest-history-page (fn [_] (browser/click ::manifest-history))]]
   [::named-page (fn [subscription] (nav/choose-left-pane subscription))
    [::details-page (fn [_] (browser/click ::subscription-details))]
    [::products-page (fn [_] (browser/click ::subscription-products))]
    [::units-page (fn [_] (browser/click ::subscription-units))]]]
  [::import-history-page])
