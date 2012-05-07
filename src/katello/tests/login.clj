(ns katello.tests.login
  (:refer-clojure :exclude [fn])
  (:use test.tree.script
        katello.conf
        katello.tasks
        slingshot.slingshot
        [bugzilla.checker :only [open-bz-bugs]]
        [com.redhat.qe.verify :only [verify-that]]))

;;; Functions

(defn navigate-toplevel [& _]
  ;;since this will be juxtaposed in with data-driven tests that take
  ;;arguments, this function needs to accept any number of args (it
  ;;will just ignore them)
  (navigate :top-level))

;;; Keywords

(defn verify-invalid-login-rejected
  "Try to login with the given credentials, verify that a proper error
  message appears in the UI."
  [username password]
  (try+
   (logout)
   (login username password)
   (when (-> (notification) :type (= :success))
     (throw (RuntimeException. "Login succeeded with bad credentials.")))
   (catch [:type :katello.tasks/invalid-credentials] _)
   (finally
    (login *session-user* *session-password*))))

(defn login-admin []
  (logout)
  (login         *session-user*         *session-password*)
  (verify-that   (= (current-user) *session-user*)))
;;; tests

(defgroup all-login-tests

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