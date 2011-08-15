(ns katello.tests.login
  (:refer-clojure :exclude [fn])
  (:use [test-clj.core :only [fn]]
        [katello.conf :only [config]]
        [error.handler :only [handle with-handlers]]
        [com.redhat.qe.verify :only [verify-that]]
        katello.tasks))

(def admin
  (fn []
    (logout)
    (login (@config :admin-user)
           (@config :admin-password))
    (verify-that (= (current-user) (@config :admin-user)))))

(def invalid
  (fn []
    (logout)
    (with-handlers [(handle :invalid-login [e])]
      (login "invalid" "sdfsdf")
      (throw (RuntimeException. "Login succeeded with bad credentials.")))))
