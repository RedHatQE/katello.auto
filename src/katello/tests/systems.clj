(ns katello.tests.systems
  (:refer-clojure :exclude [fn])
  (:require katello
            (katello [navigation :as nav]
                     [ui :as ui]
                     [rest :as rest]
                     [validation :as val]
                     [organizations :as org]
                     [environments :as env]
                     [client :as client]
                     [providers :as provider]
                     [repositories :as repo]
                     [ui-common :as common]
                     [changesets :as changeset]
                     [tasks :refer :all]            
                     [systems :as system]                   
                     [gpg-keys :as gpg-key]
                     [conf :refer [*session-user* *session-password*
                                   *session-org* config *environments*]])
            [katello.client.provision :as provision]
            [katello.tests.useful :refer [create-series]]
            (test.tree [script :refer [defgroup deftest]]
                       [builder :refer [union]])

            [serializable.fn :refer [fn]]
            [test.assert :as assert]
            [bugzilla.checker :refer [open-bz-bugs]]))


;; Functions

(defn create-test-environment []
  (def test-environment (first *environments*))
  (env/ensure-exist test-environment))

(defn register-new-test-system []
  (-> {:name "newsystem"
       :env test-environment
       :facts (system/random-facts)}
      katello/newSystem
      uniqueify
      rest/create))

(defn verify-system-rename [system]
  (nav/go-to (ui/update system update-in [:name] uniqueify)))

(defn verify-system-appears-on-env-page
  [system]
  (nav/go-to ::system/named-by-environment-page
             {:env (:env system)
              :org (-> system :env :org)
              :system system})
  (assert/is (= (:environment_id system)
                (-> test-environment rest/query :id))))

(defn configure-product-for-pkg-install
  "Creates and promotes a product with fake content repo, returns the
  product."
  [target-env]
  (with-unique [provider (katello/newProvider (:name "custom_provider" :org *session-org*))
                product (katello/newProduct {:name "fake" :provider provider})
                testkey (katello/newGPGKey {:name "mykey" :org *session-org*
                                            :contents (slurp
                                                       "http://inecas.fedorapeople.org/fakerepos/zoo/RPM-GPG-KEY-dummy-packages-generator")})
                repo (katello/newRepository {:name "zoo_repo"
                                             :product product
                                             :gpg-key testkey})]
    (env/ensure-exist target-env)
    (ui/create-all testkey provider product repo)
    (when (api/is-katello?)
      (changeset/sync-and-promote (list repo) target-env))
    product))

;; Tests

(defgroup system-tests
  :group-setup create-test-environment
  :blockers (open-bz-bugs "717408" "728357")

  (deftest "Rename an existing system"
    :blockers (open-bz-bugs "729364")
    (verify-system-rename (register-new-test-system)))


  (deftest "Verify system appears on Systems By Environment page in its proper environment"
    :blockers (open-bz-bugs "738054")

    (verify-system-appears-on-env-page (register-new-test-system)))


  (deftest "Subscribe a system to a custom product"
    :blockers (union (open-bz-bugs "733780" "736547" "784701")
                     api/katello-only)

    (with-unique [provider (katello/newProvider {:name "subscr-prov" :org *session-org*})
                  product (katello/newProduct {:name "subscribe-me"
                                               :provider provider})]
      (rest/create provider)
      (rest/create product)
      (ui/update (register-new-test-system) assoc :products (list product))))

  (deftest "Set a system to autosubscribe with no SLA preference"
    :blockers (open-bz-bugs "845261")
    (ui/update (register-new-test-system) assoc
               :auto-attach true
               :service-level "No Service Level Preference"))

  (deftest "Remove System"
    (ui/delete (register-new-test-system)))

  (deftest "Remove multiple systems"
    (let [systems (->> {:name "mysys"
                        :sockets "1"
                        :system-arch "x86_64"
                        :env test-environment} katello/newSystem uniques (take 3))]
      (rest/create-all systems)
      (system/multi-delete systems)))

  (deftest "Check whether the details of registered system are correctly displayed in the UI"
    ;;:blockers no-clients-defined

    (provision/with-client "sys-detail"
      ssh-conn
      (client/register ssh-conn
                       {:username *session-user*
                        :password *session-password*
                        :org (:name *session-org*)
                        :env (:name test-environment)
                        :force true})
      (let [hostname (client/my-hostname ssh-conn)
            details (system/get-details hostname)]
        (assert/is (= (client/get-distro ssh-conn)
                      (details "OS")))
        (assert/is (every? not-empty (vals details))))))

  (deftest "Install package group"
    :data-driven true
    :description "Add package and package group"
    :blockers api/katello-only

    (fn [package-name]
      (let [target-env (first *environments*)
            
            sys-name (uniqueify "pkg_install")
            product (configure-product-for-pkg-install target-env)]
        
        (provision/with-client sys-name
          ssh-conn
          (client/register ssh-conn
                           {:username *session-user*
                            :password *session-password*
                            :org (-> product :provider :org :name)
                            :env (:name target-env)
                            :force true})
          (let [mysys (client/my-hostname ssh-conn)]
            (client/subscribe ssh-conn (client/get-pool-id mysys product))
            (client/run-cmd ssh-conn "rpm --import http://inecas.fedorapeople.org/fakerepos/zoo/RPM-GPG-KEY-dummy-packages-generator")
            (system/add-package mysys package-name)))))

    [[{:package "cow"}]
     [{:package-group "birds"}]])
  
  (deftest "Re-registering a system to different environment"
    (let [[env-dev env-test :as envs] (->> {:name "env" :org *session-org*}
                                           katello/newEnvironment
                                           create-series
                                           (take 2))]
      (provision/with-client "reg-with-env-change"
        ssh-conn
        (let [mysys (-> {:name (client/my-hostname ssh-conn)}
                        katello/newSystem
                        rest/read)]
          (doseq [env [env-dev env-test]]
            (client/register ssh-conn
                             {:username *session-user*
                              :password *session-password*
                              :org (-> env :org :name)
                              :env (:name env)
                              :force true})
            (assert/is (= {:name env} (system/environment mysys))))
          (assert/is (not= (:environment_id mysys)
                           (rest/http-get-id env-dev)))))))
    
  
  (deftest "Install package after moving a system from one env to other"
    (let [[env-dev env-test :as envs] (->> {:name "env" :org *session-org*}
                                           katello/newEnvironment
                                           create-series
                                           (take 2))
          product (configure-product-for-pkg-install env-dev)
          package (katello/newPackage {:name "cow" :product product})]
      (provision/with-client "env_change"
        ssh-conn
        (client/register ssh-conn
                         {:username *session-user*
                          :password *session-password*
                          :org (-> env-dev :org :name)
                          :env (:name env-dev)
                          :force true})
        (let [mysys (-> {:name (client/my-hostname ssh-conn)}
                        katello/newSystem
                        rest/read)]
          (assert/is (= (:name env-dev) (system/environment mysys)))
          (ui/update mysys assoc :env env-test)
          (assert/is (= (:name env-test) (system/environment mysys)))
          (client/subscribe ssh-conn (client/get-pool-id mysys (:name product)))
          (client/run-cmd ssh-conn "rpm --import http://inecas.fedorapeople.org/fakerepos/zoo/RPM-GPG-KEY-dummy-packages-generator")
          (client/sm-cmd ssh-conn :refresh)
          (client/run-cmd ssh-conn "yum repolist")
          (expecting-error [:type :katello.systems/package-install-failed]
                           (ui/update mysys update-in [:packages] (fnil conj #{}) package))
          (let [cmd_result (client/run-cmd ssh-conn "rpm -q cow")]
            (assert/is (->> cmd_result :exit-code (= 1)))))))))
