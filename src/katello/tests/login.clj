(ns katello.tests.login
  (:refer-clojure :exclude [fn])
  (:use test.tree.script
        katello.conf
        katello.tasks
        katello.ui-tasks
        slingshot.slingshot
        [bugzilla.checker :only [open-bz-bugs]]
        [tools.verify :only [verify-that]]))

;;; Functions

(defn navigate-toplevel [& _]
  ;;to be used as a :before-test for all tests
  (navigate :top-level))

(defn verify-invalid-login-rejected
  "Try to login with the given credentials, verify that a proper error
  message appears in the UI."
  [username password]
  (try+
    (expecting-error (errtype :katello.ui-tasks/invalid-credentials) 
                     (login username password))
   (finally
    (login *session-user* *session-password*))))

(defn login-admin []
  (logout)
  (login         *session-user*         *session-password*)
  (verify-that   (= (current-user) *session-user*)))


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
