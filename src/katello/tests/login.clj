(ns katello.tests.login
  (:refer-clojure :exclude [fn])
  (:use [test-clj.core :only [fn]]
        [katello.conf :only [config]]
        [com.redhat.qe.verify :only [verify-that]]
        katello.tasks))

(def admin
  (fn []
    (logout)
    (login (@config :admin-user)
           (@config :admin-password))
    (verify-that (= (current-user) (@config :admin-user)))))
