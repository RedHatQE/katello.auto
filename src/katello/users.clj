(ns katello.users
  (:require [com.redhat.qe.auto.selenium.selenium :as sel]
            [slingshot.slingshot :refer [throw+]]
            [clojure.data :as data]
            [clj-webdriver.taxi :as browser]
            [webdriver :as wd]
            [katello :as kt]
            (katello [navigation :as nav]
                     [tasks :as tasks]
                     [ui :as ui]
                     [conf :as conf]
                     [rest :as rest]
                     [login :refer [logged-in?]]
                     [ui-common :as common]
                     [notifications :as notification])))

;; Locators

(ui/defelements :katello.deployment/any [katello.ui]
  {::roles-link                  (ui/third-level-link "user_roles")
   ::environments-link           (ui/third-level-link "environment")
   ::user-notifications          "unread_notices_count"
   ::delete-link                 (ui/link "Delete All")
   ::confirmation-no             "xpath=(//button[@type='button'])[2]"
   ::default-org-select          "org_id[org_id]"
   ::save-environment            "update_user"
   ::save-edit                   "save_password"
   ::new                         "//a[@id='new']"
   ::username-text               "user[username]"
   ::password-text               "//input[@id='password_field']" ; use id attr 
   ::confirm-text                "//input[@id='confirm_field']" ; for these two (name is the same)
   ::default-org                 "org_id[org_id]"
   ::email-text                  "user[email]"
   ::save                        "save_user"
   ::save-roles                  "save_roles"
   ::remove                      (ui/link "Remove User")
   ::enable-inline-help-checkbox "user[helptips_enabled]"
   ::clear-disabled-helptips     "clear_helptips"
   ::password-conflict           "//div[@id='password_conflict' and string-length(.)>0]"
   ::account                     "//a[contains(@class,'dropdown-item-link') and contains(.,'My Account')]"
   ::user-account-dropdown       "//nav[contains(@class,'right')]//a"
   ::switcher-button             "//a[@id='switcherButton']"
   ::current-default-org         "//div[@id='org_name']"
   ::current-default-env         "//div[@id='env_name']"
   ::add-role                    "//div[@class='available']/ul/li[@title='Read Everything']"
   ::save-button                 "//button[@type='submit']"
   ::cancel-button               "//button[@type='cancel']"})

(sel/template-fns
 {user-list-item "//div[@id='list']//div[contains(@class,'column_1') and normalize-space(.)='%s']"
  plus-icon      "//li[.='%s']//span[contains(@class,'ui-icon-plus')]"
  minus-icon      "//li[.='%s']//span[contains(@class,'ui-icon-minus')]"
  default-org    "//div[@id='orgbox']//span[../a[contains(.,'ACME_Corporation')]]"})

;; Nav

(nav/defpages :katello.deployment/any katello.menu
  [::page 
   [::named-page (fn [user] (nav/choose-left-pane user-list-item user))
    [::environments-page (nav/browser-fn (click ::environments-link))]
    [::roles-permissions-page (nav/browser-fn (click ::roles-link))]]])


;; Vars

;; since @config isn't set until runtime, make this a delay object
(def admin (delay (assert (@conf/config :admin-user))
                  (katello/newUser {:name (@conf/config :admin-user)
                                    :password (@conf/config :admin-password)
                                    :email "root@localhost"})))
;; Tasks

(defn- create
  "Creates a user with the given name and properties."
  [{:keys [name password password-confirm email default-org default-env]}]
  (nav/go-to ::page)
  (browser/click ::new)
  (let [env-chooser (fn [env] (when env
                               (nav/select-environment-widget env)))]
    (sel/fill-ajax-form [::username-text name
                         ::password-text password
                         ::confirm-text (or password-confirm password)
                         ::email-text email
                         ::default-org (:name default-org)
                         env-chooser [default-env]]
                        ::save))
  (notification/success-type :users-create))

(defn- delete "Deletes the given user."
  [user]
  (nav/go-to user)
  (browser/click ::remove)
  (browser/click ::ui/confirmation-yes)
  (notification/success-type :users-destroy))

(defn- modify-roles [to-add to-remove]
  (doseq [role to-add]
    (browser/click (plus-icon (:name role))))
  (doseq [role to-remove]
    (browser/click (minus-icon (:name role))))
  (browser/click ::save-roles)
  (notification/success-type :users-update-roles))

(defn- assign-default-org-and-env 
  "Assigns a default organization and environment to a user"
  [org env]
  (when org
    (browser/select ::default-org-select (:name org)))
  (when env
    (browser/click (ui/environment-link (:name env))))
  (browser/click ::save-environment)
  (notification/success-type :users-update-env))

(defn current
  "Returns the name of the currently logged in user, or nil if logged out."
  []
  (when (logged-in?)
    (katello/newUser {:name (browser/text ::user-account-dropdown)})))

(defn- edit
  "Edits the given user, changing any of the given properties (can
  change more than one at once). Can add or remove roles, and change
  default org and env."
  [user updated]
  (let [[to-remove {:keys [inline-help clear-disabled-helptips
                           password password-confirm email
                           default-org default-env]
                    :as to-add} _] (data/diff user updated)]
    ;; use the {username} link at upper right if we're self-editing.
    (if (= (:name (current)) (:name user))
      (do #_(browser mouseOver ::user-account-dropdown) ;; TODO : fix mouseover once this compiles
          (browser/click ::account)
          (browser/wait-until (browser/exists? ::password-text) 60000)) ; normal ajax wait doesn't work here
      (nav/go-to user))
    
    (when-not (nil? inline-help)
      (browser/select ::enable-inline-help-checkbox))
    (when password
      (browser/input-text ::password-text password)
      (browser/input-text ::confirm-text (or password-confirm password))

      ;;hack alert - force the page to check the passwords (selenium
      ;;doesn't fire the event by itself
      (browser/execute-script "window.KT.user_page.verifyPassword();")

      (when (browser/exists? ::password-conflict)
        (throw+ {:type :password-mismatch :msg "Passwords do not match"}))
      (browser/click ::save-edit) 
      (notification/success-type :users-update))
    (when email
      (common/in-place-edit {::email-text email}))
    (let [role-changes (map :roles (list to-add to-remove))]
      (when (some seq role-changes)
        (browser/click ::roles-link)
        (apply modify-roles role-changes)))
    (when (or default-org default-env)
      (browser/click ::environments-link)
      (assign-default-org-and-env default-org default-env))))

(extend katello.User
  ui/CRUD {:create #'create
           :update* #'edit
           :delete #'delete}

  rest/CRUD (let [url "api/users"
                  url-by-id (partial rest/url-maker [["api/users/%s" [identity]]])]
              {:id rest/id-field
               :query (partial rest/query-by :username :name
                               (fn [& _] (rest/api-url url)))
               :create (fn [user]
                         (rest/http-post (rest/api-url url)
                                         {:body
                                          (assoc (select-keys user [:password :disabled
                                                                    :email])
                                            :username (:name user))}))
               :read (partial rest/read-impl url-by-id)
               :update* (fn [user updated]
                          ;; TODO implement me
                          )
               :delete (fn [user]
                         (rest/http-delete (url-by-id user)))})
  
  nav/Destination {:go-to (partial nav/go-to ::named-page)}

  tasks/Uniqueable tasks/entity-uniqueable-impl)

(defn admin "Returns the admin user" []
  (kt/newUser {:name (@conf/config :admin-user)
               :password (@conf/config :admin-password)
               :email "admin@katello.org"}))

(defn delete-notifications
  "Clears out the user's notifications. If confirm is false or nil,
  the cancel button will be clicked and no notifications should be
  deleted."
  [confirm?]
  (browser/click  ::user-notifications)
  (let [num-count (browser/text ::user-notifications)]
    (browser/click ::delete-link)
    (if confirm?
      (do
        (browser/click ::ui/confirmation-yes)
        (browser/click  ::user-notifications)
        (when (not= "0" (browser/text  ::user-notifications))
          (throw+ {:type ::not-all-notifications-deleted
                   :msg "Still some notifications remained after trying to delete all"})))
      (do
        (browser/click ::confirmation-no)
        (when (not= num-count (browser/text ::user-notifications))
          (throw+ {:type ::notifications-deleted-anyway
                   :msg "Notifications were deleted even after clicking 'no' on confirm."}))))))

