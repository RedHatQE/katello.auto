(ns kalpana.locators
  (:use [com.redhat.qe.auto.selenium.selenium :only [SeleniumLocatable browser]]
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
   cp-link (LocatorTemplate. "Content Provider" "//div[@id='provider_list']//a[.='$1']")
   textbox (LocatorTemplate. "" "xpath=//*[self::input[(@type='text' or @type='password') and @name='$1'] or self::textarea[@name='$1']]")
   env-breadcrumb-link (LocatorTemplate. "Environment Breadcrumb" "//div[@id='content_envs']//a[.='$1']")
   promotion-content-category (LocatorTemplate. "Content Category" "//div[@id='left_accordion']//a[.='$1']")
   promotion-add-content-item (LocatorTemplate. "Add Content Item" "//div[@id='left_accordion']//li[normalize-space(.)='$1 Add']//a[normalize-space(.)='Add']")
   promotion-remove-content-item (LocatorTemplate. "Remove Content Item" "//div[@id='left_accordion']//li[normalize-space(.)='$1 Remove']//a[normalize-space(.)='Remove']")})

(defn- tabs "creates mapping eg: {:my-tab 'link=My Tab'}"
  [keys]
  (same-name capitalize tab keys))

(def uimap (merge
            {:error-message "//div[@class='warning']"
             :success-message "//div[@class='success']"

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
             :add-content-provider "//input[@type='submit' and @value='Add']"
             :cp-name-text (textbox "kalpana_model_provider[name]")
             :cp-description-text (textbox "kalpana_model_provider[description]")
             :cp-repository-url-text (textbox "kalpana_model_provider[repository_url]")
             :cp-type-list  "name=kalpana_model_provider[provider_type]"
             :cp-username-text (textbox "kalpana_model_provider[login_credential_attributes][username]")
             :cp-password-text (textbox "kalpana_model_provider[login_credential_attributes][password]")
             :cp-create-save "//input[@name='commit' and @value='Save']"
             :remove-content-provider "//input[@value='Remove']"
             :subscriptions (link "Subscriptions")
             :choose-file "//input[@type='file' and @id='kalpana_model_provider_contents']"
             :upload "//input[@value='Upload']"
             ;;Promotions subtab
             :products-category (promotion-content-category "Products")
             :errata-category (promotion-content-category "Errata")
             :packages-category (promotion-content-category "Packages")
             :kickstart-trees-category (promotion-content-category "Kickstart Trees")
             :promote-to-next-environment "//input[starts-with(@value,'Promote to')]"
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
                   ])))

(extend-protocol SeleniumLocatable
  clojure.lang.Keyword
  (sel-locator [k] (uimap k)))

;;page layout
(defmacro via [link]
  `(browser ~'clickAndWait ~link))

(def page-tree
  (nav-tree [:top-level [] (if-not (browser isElementPresent :log-out) (browser open "/"))
             [:content-management-tab [] (via :content-management)
              [:content-providers-tab [] (via :content-providers)
               [:new-content-provider-page [] (via :add-content-provider)]
               [:named-content-provider-page [cp-name] (via (cp-link cp-name))]]
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
              [:users-tab [] (via :users)]]]))
