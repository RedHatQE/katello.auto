(ns katello.locators
  (:use [com.redhat.qe.auto.selenium.selenium :only
         [fill-form SeleniumLocatable browser ->browser sel-locator]]
        [katello.conf :only [config]]
        [com.redhat.qe.auto.navigate :only [nav-tree]]
        [com.redhat.qe.config :only [same-name]]
        [clojure.contrib.string :only [capitalize]])
  (:import [com.redhat.qe.auto.selenium Element LocatorTemplate]
           [com.thoughtworks.selenium SeleniumException]))

;;ui layer
(extend-type String SeleniumLocatable
             (sel-locator [x] x))

(defmacro define-strategies
  "Create a function for each locator strategy in map m (which maps
  symbol to LocatorStrategy). Each function will be named the same as
  the symbol, take arguments and return a new element constructed
  with the locator strategy and args."
  [m]
  `(do ~@(for [loc-strat (keys m)]
           `(defn ~loc-strat [& args#]
              (Element. (LocatorTemplate. ~@(m loc-strat)) (into-array args#))))))

(define-strategies
  {add-repository ["Add Repository" "//div[@id='products']//div[normalize-space(.)='$1']/..//div[normalize-space(.)='Add Repository' and contains(@class, 'button')]"]
   button-div ["Button"
               "//div[contains(@class,'button') and normalize-space(.)='$1']"]
   changeset ["Changeset"
              "//div[starts-with(@id,'changeset_') and normalize-space(.)='$1']"]
   changeset-status ["Changeset status"  "//span[.='$1']/..//span[@class='changeset_status']"]
   editable ["Editable" "//div[contains(@class,'editable') and normalize-space(.)='$1']"]
   env-breadcrumb-link ["Environment Breadcrumb"
                        "//a[@class='path_link' and normalize-space(.)='$1']"]
   environment-link ["Environment"
                     "//ul[@class='breadcrumb']//a[normalize-space(.)='$1']"]

   link ["" "link=$1"]
   notification-close-index ["Notification close button"
                             "xpath=(//div[contains(@class,'jnotify-notification-error')]//a[@class='jnotify-close'])[$1]"]
   plus-icon ["Plus icon" "//li[.='$1']//span[contains(@class,'ui-icon-plus')]"]
   product-edit ["Product edit"
                 "//div[@id='products']//div[starts-with(@id, 'edit_product') and normalize-space(.)='$1']"]
   product-expand ["Expand product"
                   "//div[@id='products']//div[contains(@data-url,'products') and normalize-space(.)='$1']/..//img[@alt='Expand']"]
   schedule ["Product to schedule" "//div[normalize-space(.)='$1']"]
   promotion-add-content-item ["Add Content Item"
                               "//a[@data-display_name='$1' and contains(.,'Add')]"]
   promotion-content-category ["Content Category" "//div[@id='$1']"]
   promotion-content-item-n ["Content item by index"
                             "//div[@id='list']//li[$1]//span[@class='product-icon']"]
   promotion-remove-content-item ["Remove Content Item"
                                  "//a[@data-display_name='$1' and contains(.,'Remove')]"]
   provider-sync-checkbox ["Provider sync checkbox"
                           "//td[div[@class='clickable' and contains(.,'$1')]]/input[@type='checkbox']"]
   provider-sync-progress ["Provider progress"
                           "//tr[td/div[@class='clickable' and contains(.,'$1')]]/td[5]"]
   subscription-checkbox ["Subscription checkbox" "//div[@id='panel-frame']//td[contains(normalize-space(.),'$1')]//input[@type='checkbox']"]
   tab ["Tab" "link=$1"]
   textbox ["" "xpath=//*[self::input[(@type='text' or @type='password' or @type='file') and @name='$1'] or self::textarea[@name='$1']]"]
   user ["User" "//div[@id='list']//div[@class='column_1' and normalize-space(.)='$1']"]
   username-field ["Username field" "//div[@id='users']//div[normalize-space(.)='$1']"]})

(defn- tabs "creates mapping eg: {:my-tab 'link=My Tab'}"
  [keys]
  (same-name capitalize tab keys))

(def common {:notification "//div[contains(@class,'jnotify-notification')]"
             :error-message "//div[contains(@class,'jnotify-notification-error')]"
             :success-message "//div[contains(@class,'jnotify-notification-message')]"
             :spinner "//img[contains(@src,'spinner.gif')]"
             :save-inplace-edit "//button[.='Save']"
             :confirmation-dialog "//div[contains(@class, 'confirmation')]"
             :confirmation-yes "//div[contains(@class, 'confirmation')]//span[.='Yes']"
             :confirmation-no "//div[contains(@class, 'confirmation')]//span[.='No']"
             :search-bar "search"
             :search-submit "//button[@form='search_form']"
             ;;main banner
             :account "//li[@class='hello']/a"
             :log-out "//a[normalize-space(.)='Logout']"})

(def all-tabs (tabs [:organizations
                     :administration
                     :systems
                     :content-management
                     :dashboard
                     :by-environments
                     :subscriptions
                     :create

                     ;;subtabs
                     :providers
                     :sync-management
                     :sync-plans
                     :sync-schedule
                     :promotions
                     :users
                     :roles
                     :activation-keys

                     :registered
                     :groups
                     :general
                     :subscriptions
                     :facts
                     :packages]))

(def organizations {:new-organization "//a[@id='new']"
                    :create-organization "organization_save"
                    :org-name-text (textbox "name")
                    :org-description-text (textbox "description")
                    :org-environments (link "Environments")
                    :edit-organization (link "Edit")
                    :remove-organization (link "Remove Organization")
                    :org-description-text-edit "organization[description]"})

(def environments {:env-name-text (textbox "name")
                   :env-description-text (textbox "description")
                   :prior-environment "//select[@id='prior']"
                   :create-environment "//div[contains(@class, 'environment_create')]"
                   :new-environment "//div[normalize-space(.)='Add New Environment']"
                   :remove-environment (link "Remove Environment")
                   :env-name-text-edit "kt_environment[name]"
                   :env-description-text-edit "kt_environment[description]"
                   :env-prior-select-edit "kt_environment[prior]" })

(def providers {:new-provider "new"
                :provider-name-text  "provider[name]"
                :provider-description-text "provider[description]"
                :provider-repository-url-text "provider[repository_url]"
                :provider-type-list  "name=provider[provider_type]"
                :provider-username-text (textbox "provider[login_credential_attributes][username]")
                :provider-password-text (textbox "provider[login_credential_attributes][password]")
                :provider-cert-text (textbox "provider[certificate_attributes][contents]")
                :provider-create-save "provider_save"
                :remove-provider (link "Remove Provider")
                :subscriptions (link "Subscriptions")
                :choose-file "provider_contents"
                :upload "//input[@value='Upload']"
                :products-and-repositories "//nav[contains(@class,'subnav')]//a[contains(.,'Products')]"
             
                ;;add product
                :add-product (button-div "Add Product")
                :create-product (button-div "Create")
                :product-name-text "//*[@name='product[name]']"
                :product-description-text "//*[@name='product[description]']"
                :remove-product (link "Remove Product")
                ;;add repo
                :add-repository "//ul[//div[starts-with(@id,'edit_product') and normalize-space(.)='$1']]//div[starts-with(@id,'add_repository')]"
                :repo-name-text "repo[name]"
                :repo-url-text "repo[feed]" 
                :save-repository "save_repository_button"
                :remove-repository (link "Remove Repository")})

(def promotions {:products-category (promotion-content-category "products")
                 :expand-path "path-collapsed"
                 :errata-category (promotion-content-category "errata")
                 :packages-category (promotion-content-category "packages")
                 :kickstart-trees-category (promotion-content-category "kickstart trees")
                 :review-for-promotion "review_changeset"
                 :promote-to-next-environment "//div[@id='promote_changeset' and not(contains(@class,'disabled'))]"
                 :promotion-empty-list "//div[@id='left_accordion']//ul[contains(.,'available for promotion')]"
                 :new-changeset "//a[contains(.,'New Changeset')]"
                 :changeset-name-text (textbox "name")
                 :save-changeset "save_changeset_button"
                 :changeset-content "//div[contains(@class,'slider_two') and contains(@class,'has_content')]"})

(def users {:new-user "//a[@id='new']"
            :new-user-username-text "username_field"
            :new-user-password-text "password_field"
            :new-user-confirm-text "confirm_field"
            :save-user "save_user"
            :remove-user (link "Remove User")
            :enable-inline-help-checkbox "user[helptips_enabled]"
            :clear-disabled-helptips "clear_helptips"
            :change-password-text "password_field"
            :confirm-password-text "confirm_field"
            :save-roles "save_roles"
            :add-all (link "Add all")})

(def sync-plans {:new-sync-plan "new"
                 :sync-plan-name-text "sync_plan[name]"
                 :sync-plan-description-text "sync_plan[description]"
                 :sync-plan-interval-select "sync_plan[interval]"
                 :sync-plan-date-text "sync_plan[plan_date]"
                 :sync-plan-time-text "sync_plan[plan_time]"
                 :save-sync-plan "plan_save"})

(def systems {:system-name-text-edit "system[name]"
              :system-description-text-edit "system[description]"
              :system-location-text-edit "system[location]"
              ;;subscriptions pane
              :subscribe "commit"

              ;;Activationkeys subtab
              :new-activation-key "new"
              :activation-key-name-text "activation_key[name]"
              :activation-key-description-text "activation_key[description]"
              :activation-key-template-select "activation_key[system_template_id]"
              :save-activation-key "activation_key_save"
              :remove-activation-key (link "Remove Activation Key")})

(def roles {:new-role "//a[@id='new']"
            :new-role-name-text "role_name_field"
            :save-role "save_role_button"
            :save-user-edit "save_password"})

(def sync-schedules {:apply-sync-schedule "apply_button"})

(def uimap (merge all-tabs common organizations environments roles
                  users systems sync-plans sync-schedules promotions providers
                  { ;; login page
                   :username-text (textbox "username")
                   :password-text (textbox "password")
                   :log-in "commit"

              
                   ;;tabs with special chars in name
                   :sub-organizations (tab "Sub-Organizations")
                   :roles-and-permissions (tab "Roles & Permissions")

                   ;;Sync Management subtab
                   :synchronize-now "sync_button"}))

(extend-protocol SeleniumLocatable
  clojure.lang.Keyword
  (sel-locator [k] (uimap k)))

(defn promotion-env-breadcrumb [name & [next]]
  (Element. (format (if next "//a[.='%2$s' and contains(@class, 'path_link')]/../../..//a[.='%1$s']"
                        "//a[.='%1$s' and contains(@class, 'path_link')]")
                    name next)))

(defn inactive-edit-field "Takes a locator for an active in-place edit field, returns the inactive version" [loc]
  (format "//div[@name='%1s']" (sel-locator loc)))

(defn left-pane-item [name]
  (Element. (format  "//div[@id='list']//div[starts-with(normalize-space(.),'%s')]"
                     (let [l (.length name)]
                       (if (> l 32)
                         (.substring name 0 32) ;workaround for bz 737678
                         name)))))

;;page layout
(defn via [link & [ajax-wait-for]]
  (if ajax-wait-for
    (->browser (click link)
               (waitForVisible ajax-wait-for "15000"))
    (browser clickAndWait link)))

(defn select-environment-widget [env-name & [ next-env-name]]
  (do (when (browser isElementPresent :expand-path)
        (browser click :expand-path))
      (via (promotion-env-breadcrumb env-name next-env-name))))

(defn search [search-term]
  (fill-form {:search-bar search-term}
             :search-submit))

(defn choose-left-pane [item & [ajax-wait-for]]
  (try (browser click item)
       (catch SeleniumException se
         (do (search (-> item .getArguments first))
             (browser click item)))
       (finally (when ajax-wait-for
                  (browser waitForVisible ajax-wait-for "15000")))))

(def page-tree
  (nav-tree [:top-level [] (if (or (not (browser isElementPresent :log-out))
                                   (browser isElementPresent :confirmation-dialog))
                             (browser open (@config :server-url)))
             [:content-management-tab [] (via :content-management)
              [:providers-tab [] (via :providers)
               [:new-provider-page [] (via :new-provider :provider-name-text)]
               [:named-provider-page [provider-name] (choose-left-pane (left-pane-item provider-name) :remove-provider)
                [:provider-products-repos-page [] (do (via :products-and-repositories
                                                           :add-product)
                                                      (browser sleep 2000))
                 [:named-product-page [product-name] (do (via (editable product-name)
                                                              :product-name-text)
                                                         (browser click (product-expand product-name)))
                  [:named-repo-page [repo-name] (via (editable repo-name) :remove-repository)]]]
                [:provider-subscriptions-page [] (via :subscriptions :upload)]]]
              [:sync-management-page [] (via :sync-management)
               [:sync-plans-page [] (via :sync-plans)
                [:named-sync-plan-page [sync-plan-name]
                 (choose-left-pane (left-pane-item sync-plan-name)
                                   (inactive-edit-field :sync-plan-name-text))]
                [:new-sync-plan-page [] (via :new-sync-plan :sync-plan-name-text)]]
               [:sync-schedule-page [] (via :sync-schedule)]]
              [:promotions-page [] (via :promotions)
               [:named-environment-promotions-page [env-name next-env-name]
                (select-environment-widget env-name next-env-name)
                [:named-changeset-promotions-page [changeset-name]
                 (via (changeset changeset-name) :changeset-content)]]]]
             [:systems-tab [] (via :systems)
              [:activation-keys-page [] (via :activation-keys)
               [:named-activation-key-page [activation-key-name]
                (choose-left-pane (left-pane-item activation-key-name)
                                  (inactive-edit-field :activation-key-name-text))]
               [:new-activation-key-page [] (via :new-activation-key :activation-key-name-text)]]
              [:systems-environment-page [env-name]
               (do (via :by-environments)
                   (select-environment-widget env-name))
               [:named-system-environment-page [system-name]
                (choose-left-pane (left-pane-item system-name)
                                  (inactive-edit-field :system-name-text-edit))]]
              [:named-systems-page [system-name] (choose-left-pane
                                                  (left-pane-item system-name)
                                                  (inactive-edit-field :system-name-text-edit))
               [:system-subscriptions-page [] (via :subscriptions :subscribe)]]]
             [:organizations-tab [] (via :organizations)
              [:new-organization-page [] (via :new-organization :org-name-text)]
              [:named-organization-page [org-name] (choose-left-pane
                                                    (left-pane-item org-name)
                                                    :remove-organization) 
               [:new-environment-page [] (via :new-environment :create-environment)]
               [:named-environment-page [env-name] (via (environment-link env-name) :remove-environment)]]]
             [:administration-tab [] (via :administration)
              [:users-tab [] (via :users)
               [:named-user-page [username] (via (user username) (username-field username) )
                [:user-roles-permissions-page [] (via :roles-and-permissions :add-all)]]]
              [:roles-tab [] (via :roles)]]]))
