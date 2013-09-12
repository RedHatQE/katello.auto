(ns katello.tests.activation-keys
  (:refer-clojure :exclude [fn])
  (:require [katello :as kt]
            (katello [ui :as ui]
                     [activation-keys :as ak]                   
                     [client :as client]
                     [ui-common :as common]
                     [tasks :refer :all]
                     [rest :as rest]
                     [navigation :as nav]
                     [environments :as env]
                     [validation :as val]
                     [manifest :as manifest]
                     [redhat-repositories :as rh-repos]
                     [fake-content  :as fake]
                     [subscriptions :as subs]
                     [systems :as system]
                     [conf :refer [*session-org* *environments* config]]
                     [blockers :refer [bz-bugs]])
            [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            [katello.tests.useful :refer [create-recursive new-manifest]]
            [katello.client.provision :as provision]            
            [test.tree.script :refer [defgroup deftest]]
            [serializable.fn :refer [fn]]
            [test.assert :as assert]))

(def ces-manifest-url "http://cosmos.lab.eng.pnq.redhat.com/rhel64/manifest_ces.zip")

;; Tests

(defn some-ak [] (kt/newActivationKey {:name "ak"
                                       :env (kt/library *session-org*)
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
    
    (deftest  "Deleting the pool referenced by the activation key" 
      :uuid "600a0af7-2c55-4a65-9754-96050096891a"
      (let [dest (manifest/fetch-manifest ces-manifest-url)
            manifest (new-manifest true)
            org (kt/org manifest)
            provider (assoc kt/red-hat-provider :org org)
            manifest-1 (kt/newManifest {:file-path dest
                                        :url (@config :redhat-repo-url)
                                        :provider provider})
            repos     (for [r (rh-repos/describe-repos-to-enable-disable rh-repos/enable-redhat-repos)]
                        (update-in r [:reposet :product :provider] assoc :org org))]
        (ui/create manifest)
        (rh-repos/enable-disable-repos repos)
        (with-unique [ak (kt/newActivationKey {:name "ak"
                                               :env (kt/library org)
                                               :description "auto activation key"})]
          (ui/create ak)
          (ui/update ak assoc :subscriptions rh-repos/redhat-ak-subscriptions)
          (assert/is (some #{(first rh-repos/redhat-ak-subscriptions)}
                           (ak/get-subscriptions ak)))
          (ui/delete manifest)
          (ui/create manifest-1)
          (assert/is (not-any? #{(first rh-repos/redhat-ak-subscriptions)}
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
        (client/sm-cmd ssh-conn :refresh))))
  
  (deftest "Check whether the systems link on activation keys page works correctly"
    :uuid "35b9d9e2-ab84-4a99-a633-6135d40e6970"
    (let [manifest (new-manifest true)
          org (kt/org manifest)
          repos     (for [r (rh-repos/describe-repos-to-enable-disable rh-repos/enable-redhat-repos)]
                        (update-in r [:reposet :product :provider] assoc :org org))]
      (ui/create manifest)
      (rh-repos/enable-disable-repos repos)
      (with-unique [ak (kt/newActivationKey {:name "ak"
                                             :env (kt/library org)
                                             :description "auto activation key"})]
        (ui/create ak)
        (ui/update ak assoc :subscriptions rh-repos/redhat-ak-subscriptions)
        (provision/with-queued-client ssh-conn
          (client/register ssh-conn
                           {:org (-> ak :env :org :name)
                            :activationkey (:name ak)}) 
          (let [mysys (-> {:name (client/my-hostname ssh-conn) :env (kt/library org)}
                             katello/newSystem)]
            (nav/go-to ::ak/systems-page ak)
            (browser clickAndWait (ak/systems-link (:name mysys)))
            (assert/is (browser isElementPresent ::system/subscriptions))))))))
