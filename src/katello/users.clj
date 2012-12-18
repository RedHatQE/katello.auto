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
   ::default-org-select          "org_id[org_id]"
   ::save-environment            "update_user"
   ::save-edit                   "save_password"
   ::new                         "//a[@id='new']"
   ::username-text               "user[username]"
   ::password-text               "password_field" ; use id attr 
   ::confirm-text                "confirm_field" ; for these two (name is the same)
   ::default-org                 "org_id[org_id]"
   ::email-text                  "user[email]"
   ::save                        "save_user"
   ::save-roles                  "save_roles"
   ::remove                      (ui/link "Remove User")
   ::enable-inline-help-checkbox "user[helptips_enabled]"
   ::clear-disabled-helptips     "clear_helptips"
   ::password-conflict           "//div[@id='password_conflict' and string-length(.)>0]"
   ::account                     "//a[@class='header-widget' and contains(@href,'users')]"}
  ui/locators)

(sel/template-fns
 {user-list-item "//div[@id='list']//div[contains(@class,'column_1') and normalize-space(.)='%s']"
  plus-icon      "//li[.='%s']//span[contains(@class,'ui-icon-plus')]"})

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

(defn assign
  "Assigns the given user to the given roles. Roles should be a list
  of roles to assign."
  [{:keys [user roles]}]
  (nav/go-to ::roles-permissions-page {:username user})
  (doseq [role roles]
    (browser click (plus-icon role)))
  (browser click ::save-roles)
  (notification/check-for-success {:match-pred
                                   (notification/request-type? :users-update-roles)}))

(defn edit
  "Edits the given user, changing any of the given properties (can
  change more than one at once)."
  [username {:keys [inline-help clear-disabled-helptips
                    new-password new-password-confirm new-email]}]
  (nav/go-to ::named-page {:username username})
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
  (browser click (ui/environment-link env-name))
  (browser click ::save-environment)
  (notification/check-for-success))
