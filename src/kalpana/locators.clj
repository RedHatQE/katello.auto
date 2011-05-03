(ns kalpana.locators
  (:use [com.redhat.qe.auto.selenium.selenium :only [SeleniumLocatable browser ->browser]]
        [com.redhat.qe.auto.navigate :only [nav-tree]]
        [com.redhat.qe.config :only [same-name]]
        [clojure.contrib.string :only [capitalize]])
  (:import [com.redhat.qe.auto.selenium Element LocatorTemplate]))

;;ui layer

(defmacro define-strategies
  "Create a function for each locator strategy in map m (which maps
  symbol to LocatorStrategy). Each function will be named the same as
  the symbol, take arguments and return a new element constructed
  with the locator strategy and args."
  [m]
  `(do ~@(for [loc-strat (keys m)]
           `(defn ~loc-strat [& args#]
              (Element. ~(m loc-strat) (into-array args#))))))

(define-strategies
  {link (LocatorTemplate. "" "link=$1")
   tab (LocatorTemplate. "Tab" "link=$1")
   environment-link (LocatorTemplate. "Environment" "//div[@id='main']//ul//a[.='$1']")
   org-link (LocatorTemplate. "Organization" "//div[@id='main']//ul//a[.='$1']")
   cp-link (LocatorTemplate. "Content Provider" "//div[@id='list']//div[normalize-space(.)='$1']")
   textbox (LocatorTemplate. "" "xpath=//*[self::input[(@type='text' or @type='password' or @type='file') and @name='$1'] or self::textarea[@name='$1']]")
   env-breadcrumb-link (LocatorTemplate. "Environment Breadcrumb" "//div[@id='content_envs']//a[.='$1']")
   promotion-content-category (LocatorTemplate. "Content Category" "//div[@id='left_accordion']//a[.='$1']")
   promotion-add-content-item (LocatorTemplate. "Add Content Item" "//div[@id='left_accordion']//li[normalize-space(.)='$1 Add']//a[normalize-space(.)='Add']")
   promotion-remove-content-item (LocatorTemplate. "Remove Content Item" "//div[@id='left_accordion']//li[normalize-space(.)='$1 Remove']//a[normalize-space(.)='Remove']")
   promotion-content-item-n (LocatorTemplate. "Content item by index" "//div[@id='left_accordion']//div[contains(@class,'ui-accordion-content-active')]//li[$1]")
   provider-sync-checkbox (LocatorTemplate. "Provider sync checkbox" "//td[div[@class='clickable' and contains(.,'$1')]]/input[@type='checkbox']")
   provider-sync-progress (LocatorTemplate.  "Provider progress" "//tr[td/div[@class='clickable' and contains(.,'$1')]]/td[5]")
   add-repository (LocatorTemplate. "Add repostory button" "//div[@id='products']//div[starts-with(@id, 'edit_product') and normalize-space(.)='$1']/../ul/div[starts-with(@id,'add_repository')]")
   product-edit (LocatorTemplate. "Product edit" "//div[@id='products']//div[starts-with(@id, 'edit_product') and normalize-space(.)='$1']")
   product-expand (LocatorTemplate. "Product expand" "//div[@id='products']//div[starts-with(@id, 'edit_product') and normalize-space(.)='$1']/../div/a")
   notification-close-index (LocatorTemplate. "Notification close button" "xpath=(//a[@class='jnotify-close'])[$1]")
   })

(defn- tabs "creates mapping eg: {:my-tab 'link=My Tab'}"
  [keys]
  (same-name capitalize tab keys))

(def uimap (merge
            {:notification "//div[contains(@class,'jnotify-notification')]"
             :error-message "//div[contains(@class,'jnotify-notification-error')]"
             :success-message "//div[contains(@class,'jnotify-notification-message')]"
             :spinner "//img[contains(@src,'spinner.gif')]"
         
             ;; login page
             :username-text (textbox "username")
             :password-text (textbox "password")
             :log-in "commit"

             ;;main banner
             :search-textbox (textbox "s")
             :search-button "//button[@form='s']"
             :log-out "//a[normalize-space(.)='Logout']"
             
             ;;tabs with special chars in name
             :sub-organizations (tab "Sub-Organizations")

             ;;Organizations tab
             :new-organization (link "New Organization")
             :create-organization "//input[@name='commit' and @value='Create']"
             :org-name-text (textbox "name")
             :org-description-text (textbox "description")
             :org-environments (link "Environments")
             :edit-organization (link "Edit")
             :delete-organization (link "Delete")

             ;;Environments tab
             :env-name-text (textbox "name")
             :env-description-text (textbox "description")
             :prior-environment "//select[@id='environment_prior']"
             :create-environment "//input[@name='commit' and @value='Create']"
             :new-environment (link "New Environment")
             :delete-environment (link "Delete")
             :edit-environment (link "Edit")

             ;;Content Management tab
             :new-content-provider "new"
             :cp-name-text (textbox "provider[name]")
             :cp-description-text (textbox "provider[description]")
             :cp-repository-url-text (textbox "provider[repository_url]")
             :cp-type-list  "name=provider[provider_type]"
             :cp-username-text (textbox "provider[login_credential_attributes][username]")
             :cp-password-text (textbox "provider[login_credential_attributes][password]")
             :cp-cert-text (textbox "provider[certificate_attributes][contents]")
             :cp-create-save "provider_save"
             :remove-content-provider "//input[@value='Remove']"
             :subscriptions (link "Subscriptions")
             :choose-file "//input[@type='file' and @id='kalpana_model_provider_contents']"
             :upload "//input[@value='Upload']"
             :products-and-repositories "//nav[@class='subnav']//a[contains(.,'Products')]"
             ;;add product
             :add-product "add_product"
             :save-product "save_product_button"
             :product-name-text "product_name_field"
             :product-description-text "product_description_field"
             :product-url-text "product_url_field"
             :product-yum-checkbox "yum_type"
             :product-file-checkbox "file_type"
             ;;add repo
             ;;:add-repository 
             ;;Promotions subtab
             :products-category (promotion-content-category "Products")
             :errata-category (promotion-content-category "Errata")
             :packages-category (promotion-content-category "Packages")
             :kickstart-trees-category (promotion-content-category "Kickstart Trees")
             :promote-to-next-environment "//input[starts-with(@value,'Promote to')]"
             :promotion-empty-list "//div[@id='left_accordion']//ul[contains(.,'available for promotion')]"
             ;;Sync Management subtab
             :synchronize-now "sync_button"
             
             ;;Administration tab
             ;;Users subtab
             :new-user "//a[@id='new']"
             :new-user-username-text "username_field"
             :new-user-password-text "password_field"
             :new-user-confirm-text "confirm_field"
             :save-user "save_user"
             ;;Roles subtab
             :new-role "//a[@id='new']"
             :new-role-name-text "role_name_field"
             :save-role "save_role_button"
             
             }
             
            ;;regularly named tabs
            (tabs [:organizations
                   :administration
                   :systems
                   :content-management
                   :dashboard
                   :environments
                   :subscriptions
                   :create

                   ;;subtabs
                   :content-providers
                   :sync-management
                   :promotions
                   :users
                   :roles
                   ])))

(extend-protocol SeleniumLocatable
  clojure.lang.Keyword
  (sel-locator [k] (uimap k)))

;;page layout
(defmacro via [link & [ajax-wait-for]]
  (if ajax-wait-for
    `(->browser (~'click ~link)
                (~'waitForVisible ~ajax-wait-for "15000"))
    `(browser ~'clickAndWait ~link)))

(def page-tree
  (nav-tree [:top-level [] (if-not (browser isElementPresent :log-out) (browser open "/"))
             [:content-management-tab [] (via :content-management)
              [:content-providers-tab [] (via :content-providers)
               [:new-content-provider-page [] (via :new-content-provider :cp-name-text)]
               [:named-content-provider-page [cp-name] (via (cp-link cp-name) :remove-content-provider)
                [:provider-products-repos-page [] (via :products-and-repositories :add-product)]]]
              [:sync-management-page [] (via :sync-management)]
              [:promotions-page [] (via :promotions)
               [:named-environment-promotions-page [env-name] (via (env-breadcrumb-link env-name))]]]
             [:organizations-tab [] (via :organizations)
              [:new-organization-page [] (via :new-organization)]
              [:named-organization-page [org-name] (via (org-link org-name))
               [:edit-organization-page [] (via :edit-organization)]
               [:org-environments-page [] (via :org-environments)
                [:new-environment-page [] (via :new-environment)]
                [:named-environment-page [env-name] (via (environment-link env-name))
                 [:edit-environment-page [] (via :edit-environment)]]]]]
             [:administration-tab [] (via :administration)
              [:users-tab [] (via :users)]
              [:roles-tab [] (via :roles)]]]))
