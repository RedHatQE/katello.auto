(ns katello.tests.login
  (:refer-clojure :exclude [fn])
  (:require (katello [conf :refer :all] 
                     [ui-tasks :refer [navigate errtype]]
                     [tasks :refer :all]
                     [users :as user])
            [test.tree.script :refer :all]
            [slingshot.slingshot :refer :all]
            [bugzilla.checker :refer [open-bz-bugs]]
            [tools.verify :refer [verify-that]]))

;;; Functions

(defn navigate-toplevel [& _]
  ;;to be used as a :before-test for all tests
  (navigate :top-level))

(defn verify-invalid-login-rejected
  "Try to login with the given credentials, verify that a proper error
  message appears in the UI."
  [username password]
  (try+
    (expecting-error (errtype :katello.notifications/invalid-credentials) 
                     (user/login username password))
   (finally
    (user/login *session-user* *session-password*))))

(defn login-admin []
  (user/logout)
  (user/login         *session-user*         *session-password*)
  (verify-that   (= (user/current) *session-user*)))


;;; Tests

(defgroup login-tests

  (deftest "login as valid user"
    (login-admin)) 

  (deftest "login as invalid user"
    :data-driven true
    :blockers    (open-bz-bugs "730738")
    
    verify-invalid-login-rejected

    [["admin" ""]
     ["admin" "asdfasdf"]
     ["" ""]
     ["" "mypass"]
     ["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]]))
