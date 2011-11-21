(ns katello.locators
  (:use [com.redhat.qe.auto.selenium.selenium :only
         [fill-form SeleniumLocatable browser ->browser sel-locator load-wait no-wait]]
        [katello.conf :only [config]]
        [com.redhat.qe.auto.navigate :only [nav-tree]]
        [com.redhat.qe.config :only [same-name]]
        [clojure.string :only [capitalize]])
  (:import [com.redhat.qe.auto.selenium Element LocatorTemplate]
           [com.thoughtworks.selenium SeleniumException]))

;;ui layer

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
  {add-repository ["Add Repository" "//div[@id='products']//div[contains(.,'$1')]/..//div[normalize-space(.)='Add Repository' and contains(@class, 'button')]"]
   button-div ["Button"
               "//div[contains(@class,'button') and normalize-space(.)='$1']"]
   changeset ["Changeset"
              "//div[starts-with(@id,'changeset_') and normalize-space(.)='$1']"]
   changeset-status ["Changeset status"  "//span[.='$1']/..//span[@class='changeset_status']"]
   editable ["Editable" "//div[contains(@class, 'editable') and descendant::text()[substring(normalize-space(),2)='$1']]"]
   
   environment-link ["Environment"
                     "//div[contains(@class,'jbreadcrumb')]//a[normalize-space(.)='$1']"]

   link ["" "link=$1"]
   notification-close-index ["Notification close button"
                             "xpath=(//div[contains(@class,'jnotify-notification-error')]//a[@class='jnotify-close'])[$1]"]
   permission-org ["Permission Org" "//li[@class='slide_link' and starts-with(normalize-space(.),'$1')]"]

   plus-icon ["Plus icon" "//li[.='$1']//span[contains(@class,'ui-icon-plus')]"]
   product-edit ["Product edit"
                 "//div[@id='products']//div[contains(@data-url, 'edit') and contains(.,'$1')]"]
   product-expand ["Expand product"
                   "//div[@id='products']//div[contains(@data-url,'products') and contains(.,'$1')]/..//img[@alt='Expand']"]
   product-schedule ["Schedule for product" "//div[normalize-space(.)='$1']/following-sibling::div[1]"]
   schedule ["Product to schedule" "//div[normalize-space(.)='$1']"]
   promotion-add-content-item ["Add Content Item"
                               "//a[@data-display_name='$1' and contains(.,'Add')]"]
   promotion-content-category ["Content Category" "//div[@id='$1']"]
   promotion-content-item-n ["Content item by index"
                            "//div[@id='list']//li[$1]//div[contains(@class,'simple_link')]/descendant::text()[(position()=0 or parent::span) and string-length(normalize-space(.))>0]"]
   promotion-remove-content-item ["Remove Content Item"
                                  "//a[@data-display_name='$1' and contains(.,'Remove')]"]
   provider-sync-checkbox ["Provider sync checkbox"
                           "//td[div[@class='clickable' and contains(.,'$1')]]/input[@type='checkbox']"]
   provider-sync-progress ["Provider progress"
                           "//tr[td/div[@class='clickable' and contains(.,'$1')]]/td[5]"]
   role-action ["Role action" "//li[.//span[@class='sort_attr' and .='$2']]//a[.='$1']"]
   slide-link ["Slide Link" "//li[contains(@class,'slide_link') and normalize-space(.)='$1']"]
   subscription-checkbox ["Subscription checkbox" "//div[@id='panel-frame']//td[contains(normalize-space(.),'$1')]//input[@type='checkbox']"]
   sync-plan ["Sync Plan" "//div[@id='plans']//div[normalize-space(.)='$1']"]
   tab ["Tab" "link=$1"]
   template-product ["Template product" "//span[@class='product-icon' and starts-with(normalize-space(),'$1')]"]
   template-product-action ["Template content action" "//li[contains(@class,'slide_link') and descendant::span[@class='product-icon' and starts-with(normalize-space(),'$2')]]//a[.='$1']"]
   template-package-action ["Template package action" "//ul[@class='expand_list']/li[descendant::text()[normalize-space()='$2']]//a[.='$1']"]
   template-eligible-category ["Template category" "//div[@id='content_tree']//div[normalize-space()='$1']"]
   textbox ["" "xpath=//*[self::input[(@type='text' or @type='password' or @type='file') and @name='$1'] or self::textarea[@name='$1']]"]
   user ["User" "//div[@id='list']//div[contains(@class,'column_1') and normalize-space(.)='$1']"]
   username-field ["Username field" "//div[@id='users']//div[normalize-space(.)='$1']"]
   left-pane-field-list ["Left pane item#" "xpath=(//div[contains(@class,'ellipsis')])[$1]"]})

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
                     :all
                     :by-environments
                     :create

                     ;;subtabs
                     :providers
                     :custom
                     :red-hat
                     :sync-management
                     :sync-status
                     :sync-plans
                     :sync-schedule
                     :promotions
                     :system-templates
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
                :remove-repository (link "Remove Repository")

                ;;redhat page
                :subscriptions-items "//div[@id='subscription']//tbody/tr"
                })

(def promotions {:products-category (promotion-content-category "products")
                 :expand-path "path-collapsed"
                 :errata-category (promotion-content-category "errata")
                 :packages-category (promotion-content-category "packages")
                 :kickstart-trees-category (promotion-content-category "kickstart trees")
                 :templates-category (promotion-content-category "templates")
                 :promotion-eligible-home "//div[@id='content_tree']//div[contains(@class,'home_img_inactive')]"

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
            :new-user-default-org "org_id[org_id]"
            :new-user-email "email_field"
            :save-user "save_user"
            :remove-user (link "Remove User")
            :enable-inline-help-checkbox "user[helptips_enabled]"
            :clear-disabled-helptips "clear_helptips"
            :change-password-text "password_field"
            :confirm-password-text "confirm_field"
            :user-email-text "user[email]"
            :save-roles "save_roles"
            :add-all (link "Add all")
            :password-conflict "//div[@id='password_conflict' and string-length(.)>0]"})

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
              :save-activation-key "save_key"
              :remove-activation-key (link "Remove Activation Key")
              :subscriptions-right-nav "//div[contains(@class, 'panel-content')]//a[.='Subscriptions']"})

(def roles {:new-role "//a[@id='new']"
            :new-role-name-text "role[name]"
            :new-role-description-text "role[description]"
            :save-role "role_save"
            :save-user-edit "save_password"
            :role-users "role_users"
            :role-permissions "role_permissions"
            :next "next_button"
            :permission-resource-type-select "permission[resource_type_attributes[name]]"
            :permission-verb-select "permission[verb_values][]"
            :permission-tag-select "tags"
            :permission-name-text "permission[name]"
            :permission-description-text "permission[description]"
            :save-permission "save_permission_button"
            :remove-role "remove_role"
            :add-permission "add_permission"})

(def sync-schedules {:apply-sync-schedule "apply_button"})

(def templates {:new-template "new"
                :template-name-text "system_template[name]"
                :template-description-text "system_template[description]"
                :save-new-template "template_save" ;;when creating
                :template-eligible-package-groups (template-eligible-category "Package Groups")
                :template-eligible-packages (template-eligible-category "Packages")
                :template-package-groups (slide-link "Package Groups")
                :template-eligible-home "//div[@id='content_tree']//div[contains(@class,'home_img_inactive')]"
                :save-template "save_template"}) ;;when editing

(def uimap (merge all-tabs common organizations environments roles
                  users systems sync-plans sync-schedules promotions providers
                  templates
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
  (sel-locator [k] (uimap k))
  String
  (sel-locator [x] x))

(defn promotion-env-breadcrumb [name & [next]]
  (let [prefix "//a[normalize-space(.)='%1$s' and contains(@class, 'path_link')]"]
    (Element. (format 
               (str prefix (if next "/../../..//a[normalize-space(.)='%1$s']" ""))
               name next))))

(defn inactive-edit-field "Takes a locator for an active in-place edit field, returns the inactive version" [loc]
  (format "//div[@name='%1s']" (sel-locator loc)))

(defn left-pane-item [name]
  (Element. (LocatorTemplate. "Left pane item"
                              "//div[@id='list']//div[starts-with(normalize-space(.),'$1')]") 
            (into-array [(let [l (.length name)]
                           (if (> l 32)
                             (.substring name 0 32) ;workaround for bz 737678
                             name))])))


;;nav tricks
(defn via [link & [post-fn]]
  (browser click link)
  ((or post-fn no-wait)))

(defn select-environment-widget [env-name & [{:keys [next-env-name wait-fn]}]]
  (do (when (browser isElementPresent :expand-path)
        (browser click :expand-path))
      (via (promotion-env-breadcrumb env-name next-env-name) wait-fn)))

(defn search [search-term]
  (fill-form {:search-bar search-term}
             :search-submit (constantly nil)))

(defn choose-left-pane [item & [post-fn]]
  (try (browser click item)
       (catch SeleniumException se
         (do (search (-> item .getArguments first))
             (browser click item)))
       (finally ((or post-fn no-wait)))))

(defn toggler [[on-text off-text] loc-strategy]
  (fn [associated-text on?]
    (loc-strategy (if on? on-text off-text) associated-text)))

(def add-remove ["+ Add" "Remove"])

(def user-role-toggler (toggler add-remove role-action))
(def template-product-toggler (toggler add-remove template-product-action))
(def template-package-toggler (toggler add-remove template-package-action))

(defn toggle [a-toggler associated-text on?]
  (browser click (a-toggler associated-text on?)))

(def page-tree
  (nav-tree [:top-level [] (if (or (not (browser isElementPresent :log-out))
                                   (browser isElementPresent :confirmation-dialog))
                             (browser open (@config :server-url)))
             [:content-management-tab [] (browser mouseOver :content-management)
              [:providers-tab [] (browser mouseOver :providers)
               [:custom-providers-tab [] (via :custom load-wait)
                [:new-provider-page [] (via :new-provider)]
                [:named-provider-page [provider-name] (choose-left-pane (left-pane-item provider-name))
                 [:provider-products-repos-page [] (do (via :products-and-repositories)
                                                       (browser sleep 2000))
                  [:named-product-page [product-name] (do (via (editable product-name)))]
                  [:named-repo-page [product-name repo-name] (do (via (product-expand product-name))
                                                                 (via (editable repo-name)))]]
                 [:provider-subscriptions-page [] (via :subscriptions)]]]
               [:redhat-provider-tab [] (via :red-hat load-wait)]]
              [:sync-management-page [] (browser mouseOver :sync-management)
               [:sync-status-page [] (via :sync-status load-wait)]
               [:sync-plans-page [] (via :sync-plans load-wait)
                [:named-sync-plan-page [sync-plan-name]
                 (choose-left-pane (left-pane-item sync-plan-name))]
                [:new-sync-plan-page [] (via :new-sync-plan)]]
               [:sync-schedule-page [] (via :sync-schedule load-wait)]]
              [:promotions-page [] (via :promotions load-wait)
               [:named-environment-promotions-page [env-name next-env-name]
                (select-environment-widget env-name {:next-env-name next-env-name
                                                     :wait-fn load-wait})
                [:named-changeset-promotions-page [changeset-name]
                 (via (changeset changeset-name))]]]
              [:system-templates-page [] (via :system-templates load-wait)
               [:named-system-template-page [template-name] (via (slide-link template-name))]
               [:new-system-template-page [] (via :new-template)]]]
             [:systems-tab [] (browser mouseOver :systems)
              [:systems-all-page [] (via :all load-wait)
               [:systems-by-environment-page [] (via :by-environments load-wait)
               [:named-systems-page [system-name] (choose-left-pane
                                                   (left-pane-item system-name))
                [:system-subscriptions-page [] (via :subscriptions-right-nav)]]]]
              [:activation-keys-page [] (via :activation-keys load-wait)
               [:named-activation-key-page [activation-key-name]
                (choose-left-pane (left-pane-item activation-key-name))]
               [:new-activation-key-page [] (via :new-activation-key)]]
              [:systems-environment-page [env-name]
               (do (via :by-environments load-wait)
                   (select-environment-widget env-name))
               [:named-system-environment-page [system-name]
                (choose-left-pane (left-pane-item system-name))]]]
             [:organizations-tab [] (via :organizations load-wait)
              [:new-organization-page [] (via :new-organization)]
              [:named-organization-page [org-name] (choose-left-pane (left-pane-item org-name)) 
               [:new-environment-page [] (via :new-environment)]
               [:named-environment-page [env-name] (via (environment-link env-name))]]]
             [:administration-tab [] (browser mouseOver :administration)
              [:users-tab [] (via :users load-wait)
               [:named-user-page [username] (choose-left-pane (user username))
                [:user-roles-permissions-page [] (via :roles-and-permissions)]]]
              [:roles-tab [] (via :roles load-wait)
               [:named-role-page [role-name] (choose-left-pane (left-pane-item role-name))
                [:named-role-users-page [] (via :role-users)]
                [:named-role-permissions-page [] (via :role-permissions)]]]]]))

(def tabs '(:redhat-provider-tab 
             :roles-tab :users-tab 
             :systems-all-page
             :activation-keys-page
             :systems-by-environment-page))
