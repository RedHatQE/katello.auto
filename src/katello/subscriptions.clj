(ns katello.subscriptions
  (:require (katello [ui :as ui]                    
                     [navigation :as nav])
            [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]))

;; Locators

(ui/defelements :katello.deployment/any []
  {::new                      "new"
   ::upload-manifest          "upload_form_button"
   ::refresh-manifest         "refresh_form_button"
   ::delete-manifest          "delete_form_button"
   ::create                   "commit"
   ::repository-url-text      "provider[repository_url]"
   ::choose-file              "provider_contents"
   ::manifest-history         "//a[contains(@href,'history_items')]"
   ::manifest-link            "//fieldset/div/a[contains(@href,'access')]"
   ::redhat-login             "//label[@for='username' and contains(.,'Red Hat Login')]"})

(sel/template-fns
 {subs-exists       "//div[@class='one-line-ellipsis' and contains(.,'%s')]"
  fetch-all-history "xpath=(//div[@id='subscription_history']//td[contains(., 'imported') or contains(., 'deleted') or contains(., 'Archive') or contains(., 'Owner')])[%s]"})

;; Nav

(nav/defpages :katello.deployment/any katello.menu
  [::page
   [::new-page (nav/browser-fn (click ::new))
    [::manifest-details-page (nav/browser-fn (click ::manifest-details))]
    [::manifest-import-page (nav/browser-fn (click ::manifest-import))]
    [::manifest-history-page (nav/browser-fn (click ::manifest-history))]]
   [::named-page (fn [subscription] (nav/choose-left-pane subscription))
    [::details-page (nav/browser-fn (click ::subscription-details))]
    [::products-page (nav/browser-fn (click ::subscription-products))]
    [::units-page (nav/browser-fn (click ::subscription-units))]]]
  [::import-history-page])
