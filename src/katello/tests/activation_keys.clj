(ns katello.tests.activation-keys
  (:refer-clojure :exclude [fn])
  (:require [katello :as kt]
            (katello [ui :as ui]
                     [activation-keys :as ak]                   
                     [client :as client]
                     [ui-common :as common]
                     [tasks :refer :all]
                     [environments :as env]
                     [validation :as val]
                     [fake-content  :as fake]
                     [conf :refer [*environments*]])
            [katello.tests.useful :refer [create-recursive]]
            [katello.client.provision :as provision]            
            (test.tree [script :refer [defgroup deftest]]
                       [builder :refer [union]])
            [serializable.fn :refer [fn]]
            [test.assert :as assert]
            [bugzilla.checker :refer [open-bz-bugs]]))

;; Tests

(defn some-ak [] (kt/newActivationKey {:name "ak"
                                       :env (first *environments*)
                                       :description "auto activation key"}))

(defmacro with-unique-ak [sym & body]
  `(with-unique [~sym (some-ak)]
     ~@body))

(defgroup ak-tests
  :group-setup #(create-recursive (first *environments*))
  
  (deftest "Create an activation key"
    :blockers (open-bz-bugs "750354")
    (with-unique-ak a
      (ui/create a))
    
    (deftest "Create an activation key with i18n characters"
      :data-driven true
      (fn [name]
        (with-unique [a (assoc (some-ak) :name name)]
          (ui/create a)))
      val/i8n-chars)

    (deftest "Remove an activation key"
      (with-unique-ak a
        (ui/create a)
        (ui/delete a)))

    (deftest "activation-key-dupe-disallowed"
      (with-unique-ak a
        (val/expecting-error-2nd-try val/duplicate-disallowed
                                     (ui/create a))))

    (deftest "create activation keys with subscriptions"
      (let [org (uniqueify (kt/newOrganization {:name "redhat-org"}))
            [e1 :as envz] (take 3 (uniques (kt/newEnvironment {:name "env", :org org})))]
        (fake/setup-org envz)
        (with-unique [ak (assoc (some-ak) :env e1)]
          (ui/create ak)
          (ui/update ak assoc :subscriptions fake/subscription-names)
          (assert/is (some #{(first fake/subscription-names)}
                           (ak/get-subscriptions ak)))))))

  (deftest "Delete activation key after registering a system with it"
    (with-unique-ak ak
      (ui/create ak)
      (provision/with-client "ak-delete" ssh-conn
        (client/register ssh-conn
                         {:org (-> ak :env :org)
                          :activationkey (:name ak)})
        (ui/delete ak)
        (client/sm-cmd ssh-conn :refresh)))))
