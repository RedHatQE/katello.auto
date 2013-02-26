(ns katello.users
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            [slingshot.slingshot :refer [throw+]]
            (katello [navigation :as nav] 
                     [ui :as ui]
                     [login :refer [logged-in?]]
                     [ui-common :as common]
                     [notifications :as notification])))

;; Locators

(ui/deflocators
  {::roles-link                  (ui/menu-link "user_roles")
   ::environments-link           (ui/menu-link "environment")
   ::user-notifications          "unread_notices"
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
   ::account                     "//a[@class='header-widget' and contains(@href,'users')]"
   ::switcher-button             "//a[@id='switcherButton']"}

  ui/locators)

(sel/template-fns
 {user-list-item "//div[@id='list']//div[contains(@class,'column_1') and normalize-space(.)='%s']"
  plus-icon      "//li[.='%s']//span[contains(@class,'ui-icon-plus')]"
  minus-icon      "//li[.='%s']//span[contains(@class,'ui-icon-minus')]"
  default-org    "//div[@id='orgbox']//span[../a[contains(.,'ACME_Corporation')]]"})

;; Nav

(nav/defpages
  (common/pages)
  [::page 
   [::named-page [username] (nav/choose-left-pane user-list-item username)
    [::environments-page [] (browser click ::environments-link)]
    [::roles-permissions-page [] (browser click ::roles-link)]]])


;; Tasks

(defn create
  "Creates a user with the given name and properties."
  [username {:keys [password password-confirm email default-org default-env]}]
  (nav/go-to ::page)
  (browser click ::new)
  (let [env-chooser (fn [env] (when env
                               (nav/select-environment-widget env)))]
    (sel/fill-ajax-form [::username-text username
                         ::password-text password
                         ::confirm-text (or password-confirm password)
                         ::email-text email
                         ::default-org default-org
                         env-chooser [default-env]]
                        ::save))
  (notification/check-for-success {:match-pred (notification/request-type? :users-create)}))

(defn delete "Deletes the given user."
  [username]
  (nav/go-to ::named-page {:username username})
  (browser click ::remove)
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success {:match-pred (notification/request-type? :users-destroy)}))

(defn modify-role [{:keys [user roles plus-minus]}]
  (nav/go-to ::roles-permissions-page {:username user})
  (doseq [role roles]
    (browser click (plus-minus role)))
  (browser click ::save-roles)
  (notification/check-for-success {:match-pred
                                   (notification/request-type? :users-update-roles)}))

(defn assign
  "Assigns the given user to the given roles. Roles should be a list
  of roles to assign."
  [{:keys [user roles]}]
  (modify-role  {:user user ,
                        :roles roles
                        :plus-minus plus-icon}))

(defn unassign
  "Unassigns the given user to the given roles. Roles should be a list
  of roles to assign. Reversal of assign."
  [{:keys [user roles]}]
  (modify-role  {:user user,
                        :roles roles
                        :plus-minus minus-icon}))

(defn- edit-form [{:keys [inline-help clear-disabled-helptips
                    new-password new-password-confirm new-email default-org]}]
    (when-not (nil? inline-help)
    (browser checkUncheck ::enable-inline-help-checkbox inline-help))
  (when new-password
    (browser setText ::password-text new-password)
    (browser setText ::confirm-text (or new-password-confirm new-password))

    ;;hack alert - force the page to check the passwords (selenium
    ;;doesn't fire the event by itself
    (browser getEval "window.KT.user_page.verifyPassword();")

    (when (browser isElementPresent ::password-conflict)
      (throw+ {:type :password-mismatch :msg "Passwords do not match"}))
    (browser click ::save-edit) 
    (notification/check-for-success))
  (when new-email
    (common/in-place-edit {::email-text new-email})))

(defn self-edit
  "Edits the given user, changing any of the given properties (can
  change more than one at once)."
  [edit-map]
  (browser click ::account)
  (browser waitForElement ::password-text "10000")
  (edit-form edit-map))

(defn edit
  "Edits the given user, changing any of the given properties (can
  change more than one at once)."
  [username edit-map]
  (nav/go-to ::named-page {:username username})
  (edit-form edit-map))

(defn current
  "Returns the name of the currently logged in user, or nil if logged out."
  []
  (when (logged-in?)
    (browser getText ::account)))

(defn assign-default-org-and-env 
  "Assigns a default organization and environment to a user"
  [username org-name env-name]
  (nav/go-to ::environments-page {:username username})
  (browser select ::default-org-select org-name)
  (when env-name
    (browser click (ui/environment-link env-name)))
  (browser click ::save-environment)
  (notification/check-for-success))

(defn delete-notifications
  [delete-all?]
  (browser clickAndWait ::user-notifications)
  (let [num-count (browser getText ::user-notifications)]
    (browser click ::delete-link)
    (if delete-all?
      (do
        (browser click ::ui/confirmation-yes)
        (browser clickAndWait ::user-notifications)
        (when (not= "0" (browser getText ::user-notifications))
          (throw+ {:type ::not-all-notifications-deleted
                   :msg "Still some notifications remained after trying to delete all"})))
      (do
        (browser click ::confirmation-no)
        (when (not= num-count (browser getText ::user-notifications))
          (throw+ {:type ::notifications-deleted-anyway
                   :msg "Notifications were deleted even after clicking 'no' on confirm."}))))))

