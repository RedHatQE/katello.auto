(ns katello.tests.login
  (:refer-clojure :exclude [fn])
  (:use test.tree.script
        katello.conf
        katello.tasks
        slingshot.slingshot
        [com.redhat.qe.verify :only [verify-that]]))

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

;;; tests

(def logintests

  (deftest
    "login as admin"
    (logout)
    (login         *session-user*         *session-password*)
    (verify-that   (= (current-user) *session-user*))


    (deftest :data-driven
      "login as invalid user"
      verify-invalid-login-rejected

      [["admin" ""]
       ["admin" "asdfasdf"]
       ["" ""]
       ["" "mypass"]
       ["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]])))