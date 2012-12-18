(ns katello.login
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            [slingshot.slingshot :refer [throw+]]
            (katello [conf :refer [*session-user* *session-password* *session-org*]]
                     [ui :as ui] 
                     [ui-common :as common]
                     [organizations :as organization]
                     [notifications :as notification])))

;; Locators

(ui/deflocators {::username-text     "username"
                 ::password-text     "password"
                 ::log-in            "//input[@value='Log In' or @value='Login']"}
  ui/locators)

(defn logged-in?
  "Returns true if the browser is currently showing a page where a
  user is logged in."
  []
  (browser isElementPresent ::ui/log-out))

(defn logged-out?
  "Returns true if the login page is displayed."
  []
  (browser isElementPresent ::log-in))

(defn logout
  "Logs out the current user from the UI."
  []
  (when-not (logged-out?)
    (browser clickAndWait ::ui/log-out)))

(defn login
  "Logs in a user to the UI with the given username and password. If
   none are given, the current value of katello.conf/*session-user*
   *session-password* and *session-org* are used. If any user is
   currently logged in, he will be logged out first. If the user
   doesn't have a default org selected, the value of optional org
   provided will be selected, and optionally also select a future
   default-org. The org and default-org do not have to be the same. If
   the user does have a default already, the org and/or default-org
   will be set after logging in on the dashboard page."
  ([] (login *session-user* *session-password* {:org *session-org*}))
  ([username password & [{:keys [org default-org]}]]
     (when (logged-in?) (logout))
     (sel/fill-ajax-form {::username-text username
                          ::password-text password}
                         ::log-in)
     (let [retval (notification/check-for-success {:timeout-ms 20000})
           direct-login? (some (fn [n] (or (= "Login Successful" n)
                                          (re-find #"logging into" n)))
                               (mapcat :notices retval))]
       ;; if user only has access to one org, he will bypass org select
       (if direct-login? 
         (browser waitForPageToLoad)
         (do (Thread/sleep 3000)
             (organization/switch (or org
                                      (throw+ {:type ::login-org-required
                                               :msg (format "User %s has no default org, cannot fully log in without specifying an org."
                                                            username)}))
                                  {:default-org default-org})))
       retval)))