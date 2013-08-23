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
     (katello.notifications/flush)
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
  (if (and (logged-in?)
           (apply = (map :name (list *session-user* (user/current)))))
    (do (nav/go-to ::nav/top-level)
        (if (= (nav/current-org) "Select an Organization:") ;;see bz 857173
          (try (organization/switch (@config :admin-org))
               (catch Exception _
                 (login-admin)))))
    (login))) 

;;; Tests

(defgroup login-tests

  (deftest "login as valid user"
    :uuid "2295fbd9-e2f8-a3f4-9d13-7b68ac710e26"
    (login-admin))
  
  (deftest "User - Log out"
    :uuid "aa824b75-1370-fe84-ca53-f9c9f3878e67"
    (login-admin)
    (logout-verify)
    (login)) 

  
  
  (deftest "login as invalid user"
    :uuid "07be6cd9-235c-7094-eeeb-8de2b9f75ada"
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
    :uuid "767cc796-be6b-ace4-b93b-ddd6d9915ba3"
    :data-driven true
    verify-invalid-login-rejected

    [(fn [] [(-> *session-user* :name .toUpperCase) (-> *session-user* :password .toUpperCase)])
     (fn [] [(-> *session-user* :name .toUpperCase) (:password *session-user*)])
     (fn [] [(:name *session-user*) (-> *session-user* :password .toUpperCase)])]))
