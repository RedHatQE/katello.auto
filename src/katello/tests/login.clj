(ns katello.tests.login
  (:refer-clojure :exclude [fn])
  (:use [test.tree :only [fn]]
        [katello.conf :only [config]]
        [error.handler :only [handle with-handlers ignore]]
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
    (try (logout)
         (with-handlers [(ignore :invalid-credentials)]
           (login "invalid" "sdfsdf")
           (throw (RuntimeException. "Login succeeded with bad credentials.")))
         (finally
          (login (@config :admin-user) (@config :admin-password))))))
