(ns katello.tests.login
  (:refer-clojure :exclude [fn])
  (:use [test-clj.core :only [fn]]
        [katello.conf :only [config]]
        katello.tasks))

(def admin (fn [] (verify-success
                  #(login (@config :admin-user)
                          (@config :admin-password)))))
