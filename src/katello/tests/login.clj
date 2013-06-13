(ns katello.tests.login
  (:refer-clojure :exclude [fn])
  (:require [katello :as kt]
            (katello [navigation :as nav]
                        [conf :refer :all] 
                        [tasks :refer :all]
                        [login :refer [login logout logged-in? logged-out?]]
                        [users :as user]
                        [ui-common :as common]
                        [blockers :refer [bz-bugs]]
                        [organizations :as organization])
            [serializable.fn :refer [fn]]
            [test.tree.script :refer :all]
            [slingshot.slingshot :refer :all]
            [test.assert :as assert]))

;;; Functions



(defn verify-invalid-login-rejected
  "Try to login with the given credentials, verify that a proper error
  message appears in the UI."
  [username password]
  (try+
   (expecting-error (common/errtype :katello.notifications/invalid-credentials)
                    (login (kt/newUser {:name username, :password password})))
   (finally
     (login))))

(defn login-admin []
  (logout)
  (login)
  (assert/is (= (:name (user/current))
                (:name *session-user*))))

(defn logout-verify []
  (logout)
  (assert/is (logged-out?)))

(defn navigate-toplevel [& _]
  ;;to be used as a :before-test for all tests
  (if (logged-in?)
    (do (nav/go-to ::nav/top-level)
        (if (= (nav/current-org) "Select an Organization:") ;;see bz 857173
          (try (organization/switch (@config :admin-org))
               (catch Exception _
                 (login-admin)))))
    (login))) 

;;; Tests

(defgroup login-tests

  (deftest "login as valid user"
    (login-admin))
  
  (deftest "User - Log out"
    (login-admin)
    (logout-verify)
    (login)) 

  
  
  (deftest "login as invalid user"
    :data-driven true
    :blockers (bz-bugs "730738")
    
    verify-invalid-login-rejected

    [["admin" ""]
     ["admin" "asdfasdf"]
     ["" ""]
     ["" "mypass"]
     ["asdffoo" "asdfbar"]
     ["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]])

  
  (deftest "login case sensitivity"
    :data-driven true
    verify-invalid-login-rejected

    [(fn [] [(-> *session-user* :name .toUpperCase) (-> *session-user* :password .toUpperCase)])
     (fn [] [(-> *session-user* :name .toUpperCase) (:password *session-user*)])
     (fn [] [(:name *session-user*) (-> *session-user* :password .toUpperCase)])]))
