(ns katello.subscriptions
  (:require (katello [ui :as ui]                    
                     [navigation :as nav])
            [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]))

;; Locators

(ui/defelements :katello.deployment/any []
  {::new                      "new"
   ::upload-manifest          "upload_form_button"
   ::refresh-manifest         "refresh_form_button"
   ::create                   "commit"
   ::repository-url-text      "provider[repository_url]"
   ::choose-file              "provider_contents"
   ::fetch-history-info       "//td/span/span[contains(@class,'check_icon') or contains(@class, 'shield_icon')]"})

(sel/template-fns
 {subs-exists "//div[@class='one-line-ellipsis' and contains(.,'%s')]"})

;; Nav

(nav/defpages :katello.deployment/any katello.menu
  [::page
   [::new-page (nav/browser-fn (click ::new))]
   [::named-page (fn [subscription] (nav/choose-left-pane subscription))
    [::details-page (nav/browser-fn (click ::details))]
    [::products-page (nav/browser-fn (click ::products))]
    [::units-page (nav/browser-fn (click ::products))]]]
  [::import-history-page])
