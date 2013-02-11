(ns katello.tests.systems
  (:refer-clojure :exclude [fn])
  (:require (katello [navigation :as nav]
                     [api-tasks :as api]
                     [validation :as val]
                     [organizations :as org]
                     [client :as client]
                     [providers :as provider]
                     [repositories :as repo]
                     [ui-common :as common]
                     [changesets :as changeset]
                     [tasks :refer :all]            
                     [systems :as system]                   
                     [gpg-keys :as gpg-key]
                     [conf :refer [*session-user* *session-password* config *environments*]])
            [katello.client.provision :as provision]
            (test.tree [script :refer [defgroup deftest]]
                       [builder :refer [union]])

            [serializable.fn :refer [fn]]
            [test.assert :as assert]
            [bugzilla.checker :refer [open-bz-bugs]]))


;; Functions

(defn create-test-environment []
  (def test-environment (first *environments*))
  (api/ensure-env-exist test-environment {:prior library}))

(defn register-new-test-system []
  (api/with-env test-environment
    (api/create-system (uniqueify "newsystem")
                       {:facts (api/random-facts)})))

(defn create-multiple-systems
  [system-names]
  (doseq [system-name system-names]
    (system/create system-name {:sockets "1"
                                :system-arch "x86_64"})))

(defn verify-system-rename [system]
  (with-unique [new-name "yoursys"]
    (system/edit (:name system) {:new-name new-name})
    (nav/go-to ::system/named-page {:system-name new-name})))

(defn verify-system-appears-on-env-page
  [system]
  (nav/go-to ::system/named-by-environment-page
             {:env-name test-environment
              :system-name (:name system)})
  (assert/is (= (:environment_id system)
                (api/get-id-by-name :environment test-environment))))

(defn step-to-configure-server-for-pkg-install [product-name]
  (let [provider-name (uniqueify "custom_provider")
        repo-name (uniqueify "zoo_repo")
        target-env (first *environments*)
        org-name "ACME_Corporation"
        testkey (uniqueify "mykey")]
    (org/switch)
    (api/ensure-env-exist target-env {:prior library})
    (let [mykey (slurp "http://inecas.fedorapeople.org/fakerepos/zoo/RPM-GPG-KEY-dummy-packages-generator")]
      (gpg-key/create testkey {:contents mykey}))
    (provider/create {:name provider-name})
    (provider/add-product {:provider-name provider-name
                           :name product-name})
    (repo/add-with-key {:provider-name provider-name
                        :product-name product-name
                        :name repo-name
                        :url "http://inecas.fedorapeople.org/fakerepos/zoo/"
                        :gpgkey testkey})
    (let [products [{:name product-name :repos [repo-name]}]]
      (when (api/is-katello?)
        (changeset/sync-and-promote products library target-env)))))

;; Tests

(defgroup system-tests
  :group-setup create-test-environment
  :blockers (open-bz-bugs "717408" "728357")
  :test-setup org/before-test-switch

  (deftest "Rename an existing system"
    :blockers (open-bz-bugs "729364")
    (verify-system-rename (register-new-test-system)))


  (deftest "Verify system appears on Systems By Environment page in its proper environment"
    :blockers (open-bz-bugs "738054")

    (verify-system-appears-on-env-page (register-new-test-system)))


  (deftest "Subscribe a system to a custom product"
    :blockers (union (open-bz-bugs "733780" "736547" "784701")
                     api/katello-only)

    (with-unique [provider-name "subscr-prov"
                  product-name "subscribe-me"]
      (api/create-provider provider-name)
      (api/create-product product-name {:provider-name provider-name})
      (system/subscribe {:system-name (:name (register-new-test-system))
                         :add-products [product-name]})))

  (deftest "Set a system to autosubscribe with no SLA preference"
    :blockers (open-bz-bugs "845261")
    (system/subscribe {:system-name (:name (register-new-test-system))
                       :auto-subscribe true
                       :sla "No Service Level Preference"}))

  (deftest "Remove System"
    (with-unique [system-name "mysystem"]
      (system/create system-name {:sockets "1"
                                  :system-arch "x86_64"})
      (system/delete system-name)))

  (deftest "Remove multiple systems"
    (let [system-names (take 3 (unique-names "mysys"))]
      (create-multiple-systems system-names)
      (system/multi-delete system-names)))

  (deftest "Check whether the OS of the registered system is displayed in the UI"
    ;;:blockers no-clients-defined

    (provision/with-client "check-distro"
      ssh-conn
      (client/register ssh-conn
                       {:username *session-user*
                        :password *session-password*
                        :org "ACME_Corporation"
                        :env test-environment
                        :force true})
      (assert/is (= (client/get-distro ssh-conn)
                    (system/get-os (client/my-hostname ssh-conn))))))

  (deftest "Install package group"
    :data-driven true
    :description "Add package and package group"
    :blockers api/katello-only

    (fn [package-name]
      (let [target-env (first *environments*)
            org-name "ACME_Corporation"
            sys-name (uniqueify "pkg_install")
            product-name (uniqueify "fake")]
        (step-to-configure-server-for-pkg-install product-name)
        (provision/with-client sys-name
          ssh-conn
          (client/register ssh-conn
                           {:username *session-user*
                            :password *session-password*
                            :org org-name
                            :env target-env
                            :force true})
          (let [mysys (client/my-hostname ssh-conn)]
            (client/subscribe ssh-conn (client/get-pool-id mysys product-name))
            (client/run-cmd ssh-conn "rpm --import http://inecas.fedorapeople.org/fakerepos/zoo/RPM-GPG-KEY-dummy-packages-generator")
            (system/add-package mysys package-name)))))

    [[{:package "cow"}]
     [{:package-group "birds"}]]))
