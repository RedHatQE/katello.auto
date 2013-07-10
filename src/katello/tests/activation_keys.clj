(ns katello.tests.activation-keys
  (:refer-clojure :exclude [fn])
  (:require [katello :as kt]
            (katello [ui :as ui]
                     [activation-keys :as ak]                   
                     [client :as client]
                     [ui-common :as common]
                     [tasks :refer :all]
                     [rest :as rest]
                     [environments :as env]
                     [validation :as val]
                     [manifest :as manifest]
                     [rh-repositories :as rh-repos]
                     [fake-content  :as fake]
                     [subscriptions :as subs]
                     [conf :refer [*environments*]]
                     [blockers :refer [bz-bugs]])
            [katello.tests.useful :refer [create-recursive]]
            [katello.client.provision :as provision]            
            [test.tree.script :refer [defgroup deftest]]
            [serializable.fn :refer [fn]]
            [test.assert :as assert]))

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
    :uuid "110fb0d3-7021-71c4-7b5b-87e6896e427f"
    :blockers (bz-bugs "750354")
    (with-unique-ak a
      (ui/create a))
    
    (deftest "Create an activation key with i18n characters"
      :uuid "6caaa6a4-4a0d-1974-5653-283b0c11dd4e"
      :data-driven true
      :blockers (bz-bugs "956308")
      (fn [name]
        (with-unique [a (assoc (some-ak) :name name)]
          (ui/create a)))
      (map list val/i18n-chars))

    (deftest "Remove an activation key"
      :uuid "07ba1560-7e1c-2104-8eeb-2290b4a37a4d"
      (with-unique-ak a
        (ui/create a)
        (ui/delete a)))

    (deftest "activation-key-dupe-disallowed"
      :uuid "bfbf02a1-394e-1984-c9b3-79659706e8b9"
      (with-unique-ak a
        (val/expecting-error-2nd-try val/duplicate-disallowed
                                     (ui/create a)))))

    (deftest "create activation keys with subscriptions"
      :uuid "e33bc129-6114-4de4-6a9b-b40334236c9c"
      :blockers (list rest/katello-only)
      (let [org (uniqueify (kt/newOrganization {:name "redhat-org"}))
            [e1 :as envz] (take 3 (uniques (kt/newEnvironment {:name "env", :org org})))]
        (manifest/setup-org envz (rh-repos/describe-repos-to-enable-disable fake/enable-nature-repos))
        (with-unique [ak (assoc (some-ak) :env e1)]
          (ui/create ak)
          (ui/update ak assoc :subscriptions fake/subscription-names)
          (assert/is (some #{(first fake/subscription-names)}
                           (ak/get-subscriptions ak))))))

  (deftest "Delete activation key after registering a system with it"
    :uuid "b6a914fb-d3cf-0134-da73-4ea1ca367f71"
    :blockers (bz-bugs "959211")

    (with-unique-ak ak
      (ui/create ak)
      (provision/with-queued-client ssh-conn
        (client/register ssh-conn
                         {:org (-> ak :env :org :name)
                          :activationkey (:name ak)})
        (ui/delete ak)
        (client/sm-cmd ssh-conn :refresh)))))
  
