(ns katello.locators
  (:require [com.redhat.qe.auto.selenium.selenium :refer
              [fill-form SeleniumLocatable browser ->browser sel-locator]]
            (katello [conf :refer [config]] 
                     [tasks :refer [capitalize-all]]) 
            [ui.navigate :refer [nav-tree page-zip]]
            [clojure.string :refer [capitalize ]])
  (:import [com.redhat.qe.auto.selenium Element LocatorTemplate]
           [com.thoughtworks.selenium SeleniumException]))

;;ui layer
(defn- template [templ]
  (fn [& args] (Element. (LocatorTemplate. "" templ) (into-array args))))

(defmacro define-strategies
  "Expands into a function for each locator strategy in map m (which
  maps symbol to LocatorStrategy). Each function will be named the
  same as the symbol, take arguments and return a new element
  constructed with the locator strategy and args. See also the
  LocatorTemplate class."
  [m]
  `(do ~@(for [loc-strat (keys m)]
           `(def ~loc-strat 
              (template ~(m loc-strat))))))

(define-strategies
  {add-repository                  "//div[@id='products']//div[contains(.,'$1')]/..//div[normalize-space(.)='Add Repository' and contains(@class, 'button')]"
   auto-complete-item              "//ul[@role='listbox']//a[contains(.,'$1')]"
   button-div                      "//div[contains(@class,'button') and normalize-space(.)='$1']"
   changeset                       "//div[starts-with(@id,'changeset_') and normalize-space(.)='$1']"
   changeset-status                "//span[.='$1']/..//span[@class='changeset_status']"
   content-search-result-item-n    "//ul[@id='grid_row_headers']/li[$1]"
   content-search-package-name     "//ul[@id='grid_row_headers']/li[$1]/span/span[1]"
   content-search-compare-checkbox "//input[@type='checkbox' and @name='$1']"
   content-search-compare-checkbox-all "//div[@id='grid_content']//input[$1]"
   content-search-repo-remove      "//div[@id='repo_autocomplete_list']/ul/li[@data-name='$1']/i[contains(@class,'remove')]"
   content-search-repo-header-name "//ul[@id='column_headers']/li[$1]/span[1]"
   content-search-repo-column-name "//ul[@id='grid_row_headers']//li[contains(@data-id,'repo')][$1]"
   content-search-column           "//div/span[contains(@class,'checkbox_holder')]/input[@type='checkbox' and @data-node_name='$1']"
   default-org-star                "//div[@id='orgbox']//a[.='$1']/../span[starts-with(@id,'favorite')]"
   editable                        "//div[contains(@class, 'editable') and descendant::text()[substring(normalize-space(),2)='$1']]"
   environment-link                "//div[contains(@class,'jbreadcrumb')]//a[normalize-space(.)='$1']"
   left-pane-field-list            "xpath=(//div[contains(@class,'left')]//div[contains(@class,'ellipsis') or @class='block tall'])[$1]"
   link                            "link=$1"
   notification-close-index        "xpath=(//div[contains(@class,'jnotify-notification')]//a[@class='jnotify-close'])[$1]"
   notification-index              "xpath=(//div[contains(@class,'jnotify-notification')])[$1]"
   org-switcher                    "//div[@id='orgbox']//a[.='$1']"
   permission-org                  "//li[@class='slide_link' and starts-with(normalize-space(.),'$1')]"
   plus-icon                       "//li[.='$1']//span[contains(@class,'ui-icon-plus')]"
   product-edit                    "//div[@id='products']//div[contains(@data-url, 'edit') and contains(.,'$1')]"
   product-expand                  "//div[@id='products']//div[contains(@data-url,'products') and contains(.,'$1')]/..//img[@alt='Expand']"
   product-schedule                "//div[normalize-space(.)='$1']/following-sibling::div[1]"
   promotion-add-content-item      "//a[@data-display_name='$1' and contains(.,'Add')]"
   promotion-content-category      "//div[@id='$1']"
   promotion-content-item-n        "//div[@id='list']//li[$1]//div[contains(@class,'simple_link')]/descendant::text()[(position()=0 or parent::span) and string-length(normalize-space(.))>0]"
   promotion-remove-content-item   "//a[@data-display_name='$1' and contains(.,'Remove')]"
   provider-sync-checkbox          "//table[@id='products_table']//label[normalize-space(.)='$1']/..//input"
   provider-sync-progress          "//tr[td/label[normalize-space(.)='$1']]/td[5]"
   repo-enable-checkbox            "//table[@id='products_table']//label[normalize-space(.)='$1']/..//input"
   system-environment-checkbox     "//input[@class='node_select' and @type='checkbox' and @data-node_name='$1']" 
   role-action                     "//li[.//span[@class='sort_attr' and .='$2']]//a[.='$1']"
   schedule                        "//div[normalize-space(.)='$1']"
   search-favorite                 "//span[contains(@class,'favorite') and @title='$1']"
   search-result-repo-id           "//ul[@id='grid_row_headers']//ul[contains(@id,'child_header_list')]//li[contains(.,'$1')]"
   search-result-col-id            "//ul[@id='column_headers']//li[contains(.,'$1')]"
   search-result-row-id            "//ul[@id='grid_row_headers']/li[contains(.,'$1')]"
   search-result-cell              "//div[@id='grid_row_$1']/div[contains(@class,'cell_$2')]/i"
   slide-link                      "//li[contains(@class,'slide_link') and normalize-space(.)='$1']"
   subscription-available-checkbox "//div[@id='panel-frame']//table[@id='subscribeTable']//td[contains(normalize-space(.),'$1')]//input[@type='checkbox']"
   subscription-current-checkbox   "//div[@id='panel-frame']//table[@id='unsubscribeTable']//td[contains(normalize-space(.),'$1')]//input[@type='checkbox']"
   sync-plan                       "//div[@id='plans']//div[normalize-space(.)='$1']"
   system-checkbox                 "//input[@class='system_checkbox' and @type='checkbox' and parent::td[normalize-space(.)='$1']]" 
   tab                             "link=$1"
   template-action                 "//a[@data-name='$2' and .='$1']"
   template-eligible-category      "//div[@id='content_tree']//div[normalize-space()='$1']"
   template-product                "//span[contains(@class, 'custom-product-sprite')]/following-sibling::span/text()[contains(.,'$1')]"
   textbox                         "xpath=//*[self::input[(@type='text' or @type='password' or @type='file') and @name='$1'] or self::textarea[@name='$1']]"
   user                            "//div[@id='list']//div[contains(@class,'column_1') and normalize-space(.)='$1']"
   username-field                  "//div[@id='users']//div[normalize-space(.)='$1']"})


(defn get-all-of-locator [locatorfn] 
  "For locators that accept position and '*' as input, counts xpath-count and returns list of all aviable locators."
  (let [count (browser getXpathCount (.getLocator (locatorfn "*")))]
     (reduce (fn [acumulator number]
               (conj 
                 acumulator 
                  (locatorfn (str number))))
             []
             (range 1 (inc count)))))

(defn- tabs
  "Takes a list of keywords, and creates mapping eg: {:my-tab 'link=My Tab'}"
  [kws]
  (->> kws
     (map (comp tab
                capitalize-all
                #(.replace % "-" " ")
                name))
     (zipmap kws)))

;;
;;UI locators - mapping of names to selenium locator strings.
;;

(def common
  {:notification            "//div[contains(@class,'jnotify-notification')]"
   :notification-container  "//div[contains(@class,'jnotify-container')]"
   :error-message           "//div[contains(@class,'jnotify-notification-error')]"
   :success-message         "//div[contains(@class,'jnotify-notification-message')]"
   :spinner                 "//img[contains(@src,'spinner.gif')]"
   :save-inplace-edit       "//button[.='Save']"
   :save-inplace-edit-inputbutton       "//input[@value='Save']"
   :confirmation-dialog     "//div[contains(@class, 'confirmation')]"
   :confirmation-yes        "//div[contains(@class, 'confirmation')]//span[.='Yes']"
   :confirmation-no         "//div[contains(@class, 'confirmation')]//span[.='No']"
   :search-bar              "search"
   :search-menu             "//form[@id='search_form']//span[@class='arrow']"
   :search-save-as-favorite "search_favorite_save"
   :search-clear-the-search "search_clear"
   :search-submit           "//button[@form='search_form']"
   ;;main banner
   :account             "//a[@class='header-widget' and contains(@href,'users')]"
   :log-out             "//a[normalize-space(.)='Log Out']"
   :org-switcher        "switcherButton"
   :active-org          "//*[@id='switcherButton']"
   :default-org         "//div[@id='orgbox']//input[@checked='checked' and @class='default_org']/../"
   ;;inside the org switcher
   :manage-organizations-link  "manage_orgs"
   })

(def all-tabs
  (tabs
   (flatten
    '[:administer
      [:users
       :roles
       :manage-organizations]
      :dashboard
      :content
      [:subscriptions
       [:red-hat-subscriptions
        :activation-keys
        :import-history]
       :repositories
       [:custom-content-repositories
        :red-hat-repositories
        :package-filters
        ;; GPG Keys is defined below, because it's all caps
        ]
       :sync-management
       [:sync-status
        :sync-plans
        :sync-schedule]
       :content-search
       :system-templates
       :changeset-management
       [:changesets
        :changeset-history]]
      :systems
      [:all
       :by-environments
       :system-groups]
      

      ;;3rd level subtabs
      :create
      :details
      :registered
      :groups
      :general
      :facts
      :packages])))

(def organizations
  {:new-organization          "//a[@id='new']"
   :create-organization       "organization_submit"
   :org-name-text             "organization[name]"
   :org-description-text      "organization[description]"
   :org-environments          (link "Environments")
   :edit-organization         (link "Edit")
   :remove-organization       (link "Remove Organization")
   :org-initial-env-name-text "environment[name]"
   :org-initial-env-desc-text "environment[description]"})

(def environments
  {:env-name-text             "kt_environment[name]"
   :env-label-text             "kt_environment[label]"
   :env-description-text      "kt_environment[description]"
   :prior-environment         "kt_environment[prior]"
   :create-environment        "//input[@value='Create']"
   :new-environment           "//div[normalize-space(.)='Add New Environment']"
   :remove-environment        (link "Remove Environment")
   :env-prior-select-edit     "kt_environment[prior]" })

(def providers
  {:new-provider                        "new"
   :provider-name-text                  "provider[name]"
   :provider-description-text           "provider[description]"
   :provider-repository-url-text        "provider[repository_url]"
   :provider-cert-text                  (textbox "provider[certificate_attributes][contents]")
   :provider-create-save                "provider_submit"
   :remove-provider                     (link "Remove Provider")
   :subscriptions                       (link "Subscriptions")
   :import-manifest                     "new"
   :redhat-provider-repository-url-text "provider[repository_url]"
   :choose-file                         "provider_contents"
   :upload                              "provider_submit"
   :force-import-checkbox               "force_import"
   :products-and-repositories           "//nav[contains(@class,'subnav')]//a[contains(.,'Products')]"
                
   ;;add product
   :add-product                         (button-div "Add Product")
   :create-product                      "//input[@value='Create']"
   :product-name-text                   "//*[@name='product[name]']"
   :product-label-text                  "//*[@name='product[label]']"
   :product-description-text            "//*[@name='product[description]']"
   :remove-product                      (link "Remove Product")
   ;;add repo
   :add-repository                      "//ul[//div[starts-with(@id,'edit_product') and normalize-space(.)='$1']]//div[starts-with(@id,'add_repository')]"
   :repo-name-text                      "repo[name]"
   :repo-label-text                     "repo[label]"
   :repo-url-text                       "repo[feed]" 
   :save-repository                     "//input[@value='Create']"
   :remove-repository                   (link "Remove Repository")

   ;;redhat page
   :subscriptions-items                 "//table[@id='redhatSubscriptionTable']/tbody/tr"

   ;;gpg keys
   :gpg-key-name-text                   "gpg_key_name"
   :gpg-key-file-upload-text            "gpg_key_content_upload"
   :gpg-key-upload-button               "upload_gpg_key"
   :gpg-key-content-text                "gpg_key_content"
   :gpg-keys                            "//a[.='GPG Keys']"
   :gpg-keys-save                       "save_gpg_key"
   :new-gpg-key                         "new"
   :remove-gpg-key                      (link "Remove GPG Key")


   ;;Package Filters
   :create-new-package-filter                (link "+ New Filter")
   :new-package-filter-name                  "filter[name]"
   :new-package-filter-description           "filter[description]"
   :save-new-package-filter                  "filter_submit"
   :remove-package-filter-key                (link "Remove Filter")})
   
   
   
(def promotions
  {:products-category           (promotion-content-category "products")
   :expand-path                 "path-collapsed"
   :errata-category             (promotion-content-category "errata")
   :packages-category           (promotion-content-category "packages")
   :kickstart-trees-category    (promotion-content-category "kickstart trees")
   :templates-category          (promotion-content-category "templates")
   :promotion-eligible-home     "//div[@id='content_tree']//span[contains(@class,'home_img_inactive')]"

   :review-for-promotion        "review_changeset"
   :promote-to-next-environment "//div[@id='promote_changeset' and not(contains(@class,'disabled'))]"
   :promotion-empty-list        "//div[@id='left_accordion']//ul[contains(.,'available for promotion')]"
   :new-changeset     "//a[contains(.,'New Changeset')]"
   :changeset-name-text         "changeset[name]"
   :save-changeset              "save_changeset_button"
   :changeset-content           "//div[contains(@class,'slider_two') and contains(@class,'has_content')]"})

(def users
  {:roles-subsubtab             "//div[@class='panel-content']//a[.='Roles']"
   :environments-subsubtab      "//div[@class='panel-content']//a[.='Environments']"
   :user-default-org-select     "org_id[org_id]"
   :save-user-environment       "update_user"
   :new-user                    "//a[@id='new']"
   :user-username-text          "user[username]"
   :user-password-text          "password_field" ; use id attr 
   :user-confirm-text           "confirm_field"  ; for these two (name
                                                 ; is the same)
   :user-default-org            "org_id[org_id]"
   :user-email-text             "user[email]"
   :save-user                   "save_user"
   :remove-user                 (link "Remove User")
   :enable-inline-help-checkbox "user[helptips_enabled]"
   :clear-disabled-helptips     "clear_helptips"
   :save-roles                  "save_roles"
   :add-all                     (link "Add all")
   :password-conflict           "//div[@id='password_conflict' and string-length(.)>0]"})

(def sync-plans
  {:new-sync-plan              "new"
   :sync-plan-name-text        "sync_plan[name]"
   :sync-plan-description-text "sync_plan[description]"
   :sync-plan-interval-select  "sync_plan[interval]"
   :sync-plan-date-text        "sync_plan[plan_date]"
   :sync-plan-time-text        "sync_plan[plan_time]"
   :save-sync-plan             "plan_save"})

(def content-search
  {:content-search-type        "//select[@id='content']"
   :add-prod                   "add_product"
   :add-repo                   "add_repo"
   :row-headers                "//ul[@id='grid_row_headers']/li"
   :col-headers                "//ul[@id='column_headers']/li"
   :repo-auto-complete-radio   "repos_auto_complete_radio"
   :prod-auto-complete         "product_auto_complete"
   :repo-auto-complete         "repo_auto_complete"
   :pkg-search                 "//div[@id='package_search']/input[@id='search']"
   :errata-search              "//input[@id='search']"
   :browse-button              "//input[@id='browse_button']"
   :repo-compare-button        "//a[@id='compare_repos_btn']"
   :content-search-load-more   "//a[contains(@class,'load_row_link')]"
   :column-selector            "//div[@id='column_selector']/span[contains(@class,'path_button')]"
  })

(def systems
  {:new-system                             "new"
   :create-system                          "system_submit"
   :system-name-text                       "system[name]"
   :system-sockets-text                    "system[sockets]"
   :system-arch-select                     "arch[arch_id]"

   ;;system-edit details
   :system-name-text-edit                  "system[name]"
   :system-description-text-edit           "system[description]"
   :system-location-text-edit              "system[location]"
   :system-service-level-select            "system[serviceLevel]"
   :system-release-version-select          "system[releaseVer]"
   :system-environment                     "//div[@id='environment_path_selector']"
   :system-operating-system                "//label[contains(.,'OS')]/../following-sibling::*[1]"

   ;;systemgroups pane
   :new-system-groups                      "//a[@id='new']"
   :create-system-groups                   "group_save"
   :system-group-name-text                 "system_group[name]"
   :system-group-description-text          "system_group[description]"
   :systems-sg                             "//div[@class='panel-content']//a[.='Systems']"
   :system-groups-hostname-toadd           "add_system_input"
   :system-groups-add-system               "add_system"
   :system-groups-remove-system            "remove_systems"
   :system-group-copy                      (link "Copy")
   :system-group-copy-name-text            "name_input"
   :system-group-copy-description-text     "description_input"
   :system-group-copy-submit               "copy_button"
   :system-group-remove                    (link "Remove")
   :system-group-total                     "//fieldset[contains(.,'Total')]/div[2]/a"
   :system-group-confirm-only-system-group "//span[.='No, only delete the system group.']"
   :system-group-unlimited                 "//input[@class='unlimited_members']"
   :save-new-limit                          "//button[.='Save']"
   :system-group-limit-value               "system_group[max_systems]"
   
   ;;subscriptions pane
   :subscribe                              "sub_submit"
   :unsubscribe                            "unsub_submit"

   ;;Activationkeys subtab
   :new-activation-key                     "new"
   :activation-key-name-text               "activation_key[name]"
   :activation-key-description-text        "activation_key[description]"
   :activation-key-template-select         "activation_key[system_template_id]"
   :save-activation-key                    "save_key"
   :remove-activation-key                  (link "Remove Activation Key")
   :subscriptions-right-nav                "//div[contains(@class, 'panel-content')]//a[.='Subscriptions']"
   :release-version-text                   "system[releaseVer]"})

(def roles
  {:new-role                        "//a[@id='new']"
   :new-role-name-text              "role[name]"
   :new-role-description-text       "role[description]"
   :save-role                       "role_save"
   :save-user-edit                  "save_password"
   :role-users                      "role_users"
   :role-permissions                "role_permissions"
   :next                            "next_button"
   :permission-resource-type-select "permission[resource_type_attributes[name]]"
   :permission-verb-select          "permission[verb_values][]"
   :permission-tag-select           "tags"
   :permission-name-text            "permission[name]"
   :permission-description-text     "permission[description]"
   :save-permission                 "save_permission_button"
   :remove-role                     "remove_role"
   :add-permission                  "add_permission"})

(def sync-schedules
  {:apply-sync-schedule "apply_button"})

(def templates
  {:new-template                     "new"
   :template-name-text               "system_template[name]"
   :template-description-text        "system_template[description]"
   :save-new-template                "template_save" ;;when creating
   :template-eligible-package-groups (template-eligible-category "Package Groups")
   :template-eligible-packages       (template-eligible-category "Packages")
   :template-eligible-repositories   (template-eligible-category "Repositories")
   :template-package-groups          (slide-link "Package Groups")
   :template-eligible-home           "//div[@id='content_tree']//span[contains(@class,'home_img_inactive')]"
   :save-template                    "save_template"}) ;;when editing

;;merge all the preceeding maps together, plus a few more items.
(def ^{:doc "All the selenium locators for the Katello UI. Maps a
  keyword to the selenium locator. You can pass the keyword to
  selenium just the same as you would the locator string. See also
  SeleniumLocatable protocol."}
  uimap
  (merge all-tabs common organizations environments roles users systems sync-plans
         content-search sync-schedules promotions providers templates
         { ;; login page
          :username-text     "username"
          :password-text     "password"
          :log-in            "//input[@value='Log In' or @value='Login']"

              
          ;;tabs with special chars in name
          :sub-organizations (tab "Sub-Organizations")
                   

          ;;Sync Management subtab
          :synchronize-now   "sync_button"}))

;;Tells the clojure selenium client where to look up keywords to get
;;real selenium locators (in uimap in this namespace).
(extend-protocol SeleniumLocatable
  clojure.lang.Keyword
  (sel-locator [k] (uimap k))
  String
  (sel-locator [x] x))

(defn promotion-env-breadcrumb
  "Locates a link in the environment breadcrumb UI widget. If there
  are multiple environment paths, and you wish to select Library,
  'next' is required."
  [name & [next]]
  (let [prefix "//a[normalize-space(.)='%s' and contains(@class, 'path_link')"]
    (Element. (format 
               (str prefix (if next " and ../../..//a[normalize-space(.)='%s']" "") "]")
               name next))))

(defn inactive-edit-field
  "Takes a locator for an active in-place edit field, returns the
  inactive version"
  [loc]
  (format "//div[@name='%1s']" (sel-locator loc)))

(defn left-pane-item
  "Returns a selenium locator for an item in a left
   pane list (by the name of the item)"
  [name]
  (Element. (LocatorTemplate. "Left pane item"
                              "//div[@id='list']//div[starts-with(normalize-space(.),'$1')]") 
            (into-array [(let [l (.length name)]
                           (if (> l 32)
                             (.substring name 0 32) ;workaround for bz 737678
                             name))])))

(defn content-search-expand-strategy
  "Returns a locator strategy function for the expansion of the
  current row. The function returned will get any cell by index
  number."
  [current-loc n]
  (template (format "%s/../ul[%s]/li[$1]" current-loc n)))

;;nav tricks
(defn select-environment-widget [env-name & [{:keys [next-env-name wait]}]]
  (do (when (browser isElementPresent :expand-path)
        (browser click :expand-path))
      (browser click (promotion-env-breadcrumb env-name next-env-name))
      (when wait (browser waitForPageToLoad))))

(defn search [search-term]
  (fill-form {:search-bar search-term}
             :search-submit (constantly nil)))

(defn choose-left-pane
  "Selects an item in the left pane. If the item is not found, a
   search is performed and the select is attempted again. Takes an
   optional post-fn to perform afterwards."
  [item]
  (try (browser click item)
       (catch SeleniumException se
         (do (search (-> item .getArguments first))
             (browser click item)))))

(defn toggler
  "Returns a function that returns a locator for the given on/off text
   and locator strategy. Used for clicking things like +Add/Remove for
   items in changesets or permission lists."
  [[on-text off-text] loc-strategy]
  (fn [associated-text on?]
    (loc-strategy (if on? on-text off-text) associated-text)))

(def add-remove ["+ Add" "Remove"])

(def user-role-toggler (toggler add-remove role-action))
(def template-toggler (toggler add-remove template-action))


(defn toggle "Toggles the item from on to off or vice versa."
  [a-toggler associated-text on?]
  (browser click (a-toggler associated-text on?)))

;;
;;Navigation tree - shows all the navigation paths through the ui.
;;this data is used by the katello.tasks/navigate function to get to
;;the given page.
(def
  ^{:doc "The navigation layout of the UI. Each item in the tree is
  a new page or tab, that you can drill down into from its parent
  item. Each item contains a keyword to refer to the location in the
  UI, a list of any arguments needed to navigate there (for example,
  to navigate to a provider details page, you need the name of the
  provider). Finally some code to navigate to the location from its
  parent location. See also katello.tasks/navigate."}
  page-tree
  (page-zip
   (nav-tree
    [:top-level [] (if (or (not (browser isElementPresent :log-out))
                           (browser isElementPresent :confirmation-dialog))
                     (browser open (@config :server-url)))
   
     [:content-tab [] (browser mouseOver :content)
      [:subscriptions-tab [] (browser mouseOver :subscriptions)
       [:redhat-subscriptions-page [] (browser clickAndWait :red-hat-subscriptions)]
       [:activation-keys-page [] (browser clickAndWait :activation-keys)
        [:named-activation-key-page [activation-key-name]
         (choose-left-pane (left-pane-item activation-key-name))]
        [:new-activation-key-page [] (browser click :new-activation-key)]]]
      [:repositories-tab [] (browser mouseOver :repositories)
       [:custom-content-repositories-page [] (browser clickAndWait :custom-content-repositories)
        [:new-provider-page [] (browser click :new-provider)]
        [:named-provider-page [provider-name] (choose-left-pane (left-pane-item provider-name))
         [:provider-products-repos-page [] (->browser (click :products-and-repositories)
                                                      (sleep 2000))
          [:named-product-page [product-name] (browser click (editable product-name))]
          [:named-repo-page [product-name repo-name] (browser click (editable repo-name))]]
         [:provider-details-page [] (browser click :details)]
         [:provider-subscriptions-page [] (browser click :subscriptions)]]]
       [:redhat-repositories-page [] (browser clickAndWait :red-hat-repositories)]
       [:gpg-keys-page [] (browser clickAndWait :gpg-keys)
        [:new-gpg-key-page [] (browser click :new-gpg-key)]
        [:named-gpgkey-page [gpg-key-name] (choose-left-pane (left-pane-item gpg-key-name))]]
       [:package-filters-page [] (browser clickAndWait :package-filters)
        [:new-package-filter-page [] (browser click :create-new-package-filter)]
        [:named-package-filter-page [package-filter-name] (choose-left-pane (left-pane-item package-filter-name))]]]
      [:sync-management-page [] (browser mouseOver :sync-management)
       [:sync-status-page [] (browser clickAndWait :sync-status)]
       [:sync-plans-page [] (browser clickAndWait :sync-plans)
        [:named-sync-plan-page [sync-plan-name]
         (choose-left-pane (left-pane-item sync-plan-name))]
        [:new-sync-plan-page [] (browser click :new-sync-plan)]]
       [:sync-schedule-page [] (browser clickAndWait :sync-schedule)]]
      [:changeset-promotion-history-page [] (browser clickAndWait :changeset-history)]
      [:changeset-promotions-tab [] (browser mouseOver :changeset-management)
       [:changesets-page [] (browser clickAndWait :changesets)
        [:named-environment-changesets-page [env-name next-env-name]
         (select-environment-widget env-name {:next-env-name next-env-name :wait true})
         [:named-changeset-page [changeset-name]
          (browser click (changeset changeset-name))]]]]
      [:content-search-page [] (browser clickAndWait :content-search)]
      [:system-templates-page [] (browser clickAndWait :system-templates)
       [:named-system-template-page [template-name] (browser click (slide-link template-name))]
       [:new-system-template-page [] (browser click :new-template)]]]
     [:systems-tab [] (browser mouseOver :systems)
      [:systems-all-page [] (browser clickAndWait :all)
       [:new-system-page [] (browser click :new-system)]
       [:system-subscriptions-page [system-name] (choose-left-pane (left-pane-item system-name))
        [:named-systems-page [] (browser click :details)]]]
      [:system-groups-page [] (browser clickAndWait :system-groups)
       [:new-system-groups-page [] (browser click :new-system-groups)]
       [:named-system-group-page [system-group-name] (choose-left-pane (left-pane-item system-group-name))
        [:system-group-systems-page [] (browser click :systems-sg)]
        [:system-group-details-page [] (browser click :details)]]]
      [:systems-by-environment-page [] (browser clickAndWait :by-environments)
       [:systems-environment-page [env-name] (select-environment-widget env-name)
        [:named-system-environment-page [system-name]
         (choose-left-pane (left-pane-item system-name))]]]]
     [:organizations-page-via-org-switcher [] (browser click :org-switcher)
      [:organizations-link-via-org-switcher [] (browser clickAndWait :manage-organizations-link)
       [:new-organization-page-via-org-switcher [] (browser click :new-organization)]]]
     [:administer-tab [] (browser mouseOver :administer)
      [:users-page [] (browser clickAndWait :users)
       [:named-user-page [username] (choose-left-pane (user username))
        [:user-environments-page [] (browser click :environments-subsubtab)]
        [:user-roles-permissions-page [] (browser click :roles-subsubtab)]]]
      [:roles-page [] (browser clickAndWait :roles)
       [:named-role-page [role-name] (choose-left-pane (left-pane-item role-name))
        [:named-role-users-page [] (browser click :role-users)]
        [:named-role-permissions-page [] (browser click :role-permissions)]]]
      [:manage-organizations-page [] (browser clickAndWait :manage-organizations)
       [:new-organization-page [] (browser click :new-organization)]
       [:named-organization-page [org-name] (choose-left-pane (left-pane-item org-name)) 
        [:new-environment-page [] (browser click :new-environment)]
        [:named-environment-page [env-name] (browser click (environment-link env-name))]]]]])))


(def tab-list '(:roles-page
                :users-page 
                :systems-all-page
                :activation-keys-page
                :systems-by-environment-page))

(def ^{:doc "Tabs that don't exist in headpin"}
  katello-only-tabs
  '(:redhat-repositories-page))
