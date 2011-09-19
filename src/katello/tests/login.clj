(ns katello.tests.login
  (:refer-clojure :exclude [fn])
  (:use [test.tree :only [fn]]
        [katello.conf :only [config *session-user* *session-password*]]
        [error.handler :only [handle with-handlers ignore]]
        [com.redhat.qe.verify :only [verify-that]]
        katello.tasks))

(def admin
  (fn []
    (logout)
    (login *session-user* *session-password*)
    (verify-that (= (current-user) *session-user*))))

(def invalid
  (fn [user pw]
    (try (logout)
         (with-handlers [(ignore :invalid-credentials)]
           (login user pw)
           (throw (RuntimeException. "Login succeeded with bad credentials.")))
         (finally
          (login *session-user* *session-password*)))))

(def invalid-logins [["admin" ""]
                     ["admin" "asdfasdf"]
                     ["" ""]
                     ["" "mypass"]
                     ["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                       "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]])
