(ns katello.tests.login
  (:refer-clojure :exclude [fn])
  (:use [serializable.fn :only [fn]]
        [katello.conf :only [config *session-user* *session-password*]]
        [slingshot.slingshot]
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
         (try+ 
           (login user pw)
           (when (-> (notification) :type (= :success))
             (throw (RuntimeException. "Login succeeded with bad credentials.")))
           (catch [:type :katello.tasks/invalid-credentials] _))
         (finally
          (login *session-user* *session-password*)))))

(def invalid-logins [["admin" ""]
                     ["admin" "asdfasdf"]
                     ["" ""]
                     ["" "mypass"]
                     ["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                       "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]])
