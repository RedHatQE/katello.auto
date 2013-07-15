(ns katello.login
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            [clj-webdriver.taxi :as taxi]
            [webdriver :as wd]
            [slingshot.slingshot :refer [throw+]]
            (katello [conf :refer [*session-user* *session-org*]]
                     [ui :as ui]
                     [ui-common :as common]
                     [organizations :as organization]
                     [notifications :as notification])))

;; Locators

(ui/defelements :katello.deployment/any [katello.ui]
  {::username-text     "//input[@class='username']"
   ::password-text     "//input[@class='password']"
   ::log-in            "//input[@value='Log In' or @value='Login']"
   ::re-log-in-link    "//a[contains(@href, '/login')]"
   ::error-message     "//ul[@class='error']"
   ::close-error       "//div[@id='notifications']//div[@class='control']"
   ::interstitial      "//a[contains(@class,'menu-item-link') and contains(.,'Select an Organization')]"}
  )

(defn logged-in?
  "Returns true if the browser is currently showing a page where a
  user is logged in."
  []
  (taxi/exists? ::ui/log-out))

(defn wait-for-login
  "Waits until logout is present."
  []
  (taxi/wait-until (taxi/exists? ::ui/log-out) 60000))

(defn logged-out?
  "Returns true if the login page is displayed."
  []
  (or (taxi/exists? ::re-log-in-link)
      (taxi/exists? ::log-in)))

(defn logout
  "Logs out the current user from the UI."
  []
  (when-not (logged-out?)
    (taxi/click ::ui/log-out)))

(defn- signo-error? []
  (and (taxi/exists? ::error-message)
       (taxi/visible? ::error-message)))

(defn- clear-signo-errors []
  (taxi/click ::close-error))

(defn login
  "Logs in a user to the UI with the given user and password. If none
   are given, the current value of katello.conf/*session-user* and
   *session-org* are used. If any user is currently logged in, he will
   be logged out first. If the user doesn't have a default org
   selected, the value of optional org provided will be selected, and
   optionally also select a future default-org. The org and
   default-org do not have to be the same. If the user does have a
   default already, the org and/or default-org will be set after
   logging in on the dashboard page."
  ([] (login *session-user* {:org *session-org*}))
  ([{:keys [name password] :as user} & [{:keys [org default-org]}]]
     (when (logged-in?) (logout))
     (when (taxi/exists? ::re-log-in-link)
       (taxi/click ::re-log-in-link))

     (when (signo-error?)
       (clear-signo-errors))
     
     (taxi/quick-fill-submit {::username-text name}
                             {::password-text password}
                             {::log-in taxi/click})
     ;; throw errors
     ;;(notification/verify-no-error)     ; katello notifs
     ;;(notification/flush)
     
     (if (signo-error?)                 ; signo notifs
       (throw+ (list (ui/map->Notification {:level :error
                                            :notices (list (taxi/text ::error-message))}))))
     ;; no interstitial for signo logins, if we go straight to default org, and that's the
     ;; org we want, switch won't click anything
     #_(browser ajaxWait)
     #_(when org
       (organization/switch org {:default-org default-org}))))

(defmacro with-user-temporarily
  "Logs in as user, executes body with *session-user* bound to user,
   then finally logs back in as original *session-user*."
  [user & body]
  `(try (binding [*session-user* ~user]
          (login)
          ~@body)
        (finally (login))))
