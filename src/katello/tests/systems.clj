(ns katello.tests.systems
  (:refer-clojure :exclude [fn])
  (:require [katello :as kt]
            (katello [navigation :as nav]
                     [notifications :as notification]
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
                     [conf :refer [*session-user* *session-org* config *environments*]])
            [katello.client.provision :as provision]
            [katello.tests.useful :refer [create-all-recursive create-series create-recursive fresh-repo]]
            [clojure.string :refer [blank?]]
            [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            (test.tree [script :refer [defgroup deftest]]
                       [builder :refer [union]])

            [serializable.fn :refer [fn]]
            [test.assert :as assert]
            [bugzilla.checker :refer [open-bz-bugs]]))


;; Functions

(defn create-test-environment []
  (def test-environment (first *environments*))
  (create-recursive test-environment))

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
    (create-recursive target-env)
    (ui/create-all testkey provider product repo)
    (when (rest/is-katello?)
      (changeset/sync-and-promote (list repo) target-env))
    product))

(defn verify-new-system-tooltip
  "Confirms that tooltips in the New System form appear and show the correct messages"
  [icon-locator expected-text]
  (nav/go-to ::system/new-page)
  (browser mouseOver icon-locator)
  (Thread/sleep 2000)
  (assert/is (browser isTextPresent expected-text))
  (browser mouseOut icon-locator)
  (Thread/sleep 2000)
  (assert/is (not (browser isTextPresent expected-text))))


(defn validate-system-facts
  [name cpu arch virt? env]
  (nav/go-to ::system/facts-page {:system-name name})
  (browser click ::system/cpu-expander)
  (assert/is (= cpu (browser getText ::system/cpu-socket)))
  (browser click ::system/network-expander)
  (assert/is (= name (browser getText ::system/net-hostname)))
  (browser click ::system/uname-expander)
  (assert/is (= arch (browser getText ::system/machine-arch)))
  (browser click ::system/virt-expander)
  (if virt?
    (assert/is (= "true" (browser getText ::system/virt-status)))
    (assert/is (= "false" (browser getText ::system/virt-status))))
  (let [details (system/get-details name)]
    (assert/is (= name (details "Name")))
    (assert/is (= arch (details "Arch")))
    (assert/is (= env  (details "Environment")))))

;; Tests

(defgroup system-tests
  :group-setup create-test-environment
  :blockers (open-bz-bugs "717408" "728357")

  (deftest "Rename an existing system"
    :blockers (open-bz-bugs "729364")
    (verify-system-rename (register-new-test-system)))

  (deftest "System-details: Edit system"
    :data-driven true
                                        ;:blockers (open-bz-bugs "917033")

    (fn [new-system save? & [expected-err]]
      (let [system (uniqueify "newsystem")]
        (system/create system {:sockets "1"
                               :system-arch "x86_64"})
        (if expected-err
          (expecting-error (common/errtype expected-err)
                           (system/edit-sysname system new-system save?))
          (system/edit-sysname system new-system save?))))

    [["yoursys" false]
     ["test.pnq.redhat.com" true]
     [(random-string (int \a) (int \z) 256) true :katello.notifications/system-name-255-char-limit]
     [(random-string (int \a) (int \z) 255) true]])

  (deftest "System-details: Edit Description"
    :data-driven true

    (fn [new-description save? & [expected-err]]
      (let [system (uniqueify "newsystem")]
        (system/create system {:sockets "1"
                               :system-arch "x86_64"})
        (if expected-err
          (expecting-error (common/errtype expected-err)
                           (system/edit-sys-description system new-description save?))
          (system/edit-sys-description system new-description save?))))

    [["cancel description" false]
     ["System Registration Info" true]
     [(random-string (int \a) (int \z) 256) true :katello.notifications/sys-description-255-char-limit]
     [(random-string (int \a) (int \z) 255) true]])

  (deftest "Verify system appears on Systems By Environment page in its proper environment"
    :blockers (open-bz-bugs "738054")

    (verify-system-appears-on-env-page (register-new-test-system)))


  (deftest "Subscribe a system to a custom product"
    :blockers (union (open-bz-bugs "733780" "736547" "784701")
                     rest/katello-only)

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

  
  (deftest "Remove systems and validate sys-count"
    (with-unique [org (kt/newOrganization {:name "delsyscount"})
                  env (kt/newEnvironment {:name "dev", :org org})]
      (let [systems (->> {:name "delsys", :env env}
                         kt/newSystem
                         uniques
                         (take 4))
            ui-count #(Integer/parseInt (browser getText ::system/total-sys-count))]
        (create-all-recursive systems)
        (assert/is (= (count systems) (ui-count)))
        (ui/delete (first systems))
        (assert/is (= (dec (count systems)) (ui-count)))
        (system/multi-delete (rest systems))
        (assert/is (= 0 (ui-count))))))

  (deftest "Remove System: with yes-no confirmation"
    :data-driven true

    (fn [confirm?]
      (with-unique [system (kt/newSystem {:name "mysystem"
                                          :sockets "1"
                                          :system-arch "x86_64"})]
        (ui/create system)
        (nav/go-to system)
        (browser click ::remove)
        (if confirm?
          (do (browser click ::ui/confirmation-yes)
              (notification/check-for-success {:match-pred (notification/request-type? :sys-destroy)})
              (assert (rest/not-exists? system)))
          (do (browser click ::confirm-to-no)
              (nav/go-to system)))))
    [[false]
     [true]])

  (deftest "System Details: Add custom info"
    :blockers (open-bz-bugs "919373")
    (with-unique [system (kt/newSystem {:name "mysystem"})]
      (ui/create system)
      (ui/update system assoc :custom-info "Hypervisor" "KVM")))

  (deftest "System Details: Update custom info"
    :blockers (open-bz-bugs "919373")
    (with-unique [system (kt/newSystem {:name "mysystem"})]
      (ui/create system)
      (ui/update system assoc :custom-info "Hypervisor" "KVM")
      (ui/update system assoc :custom-info "Hypervisor" "Xen")))

  (deftest "System Details: Delete custom info"
    :blockers (open-bz-bugs "919373")
    (with-unique [system (kt/newSystem {:name "mysystem"})]
      (ui/create system)
      (ui/update system assoc :custom-info "Hypervisor" "KVM")
      (ui/update system update-in [:custom-info] dissoc "Hypervisor")))

  (deftest "System name is required when creating a system"
    (expecting-error val/name-field-required
                     (ui/create (kt/newSystem {:name "" :facts (system/random-facts)}))))
  ;; FIXME - convert-to-records

  #_((deftest "New System Form: tooltips pop-up with correct information"
       :data-driven true
       verify-new-system-tooltip
       [[::system/ram-icon "The amount of RAM memory, in megabytes (MB), which this system has"]
        [::system/sockets-icon "The number of CPU Sockets or LPARs which this system uses"]])

     (deftest "Add system from UI"
       :data-driven true

       (fn [virt?]
         (with-unique [env "dev"
                       system "mysystem"]
           (let [arch "x86_64"
                 cpu "2"]
             (env/create env {:org-name (@config :admin-org)})
             (system/create-with-details system {:sockets cpu
                                                 :system-arch arch :type-is-virtual? virt? :env env})
             (validate-system-facts system cpu arch virt? env))))

       [[false]
        [true]])

     (deftest "Add system link is disabled when org has no environments"
       (with-unique [org (kt/newOrganization {:name "addsys"})]
         (rest/create org)
         (nav/go-to ::system/page)
         (let [{:strs [original-title class]} (browser getAttributes ::system/new)]
           (assert (and (.contains class "disabled")
                        (.contains original-title "environment is required"))))))

     (deftest "Check whether the details of registered system are correctly displayed in the UI"
       ;;:blockers no-clients-defined
       (provision/with-client "sys-detail"
         ssh-conn
         (client/register ssh-conn
                          {:username (:name *session-user*)
                           :password (:password *session-user*)
                           :org (:name *session-org*)
                           :env (:name test-environment)
                           :force true})
         (let [hostname (client/my-hostname ssh-conn)1
               details (system/get-details hostname)]
           (assert/is (= (client/get-distro ssh-conn)
                         (details "OS")))
           (assert/is (every? not-empty (vals details)))
           (assert/is (= (client/get-ip-address ssh-conn)
                         (system/get-ip-addr hostname)))))))

  ;;FIXME - convert-to-records

  #_((deftest "Review Facts of registered system"
       ;;:blockers no-clients-defined
       (provision/with-client "sys-facts"
         ssh-conn
         (let [target-env (first *environments*)]
           (client/register ssh-conn {:username *session-user*
                                      :password *session-password*
                                      :org (@config :admin-org)
                                      :env target-env
                                      :force true})
           (let [hostname (client/my-hostname ssh-conn)
                 facts (system/get-facts hostname)]
             (system/expand-collapse-facts-group hostname)
             (assert/is (every? (complement empty?) (vals facts)))))))

     (deftest "System-Details: Validate Activation-key link"
       (with-unique [system "mysystem"
                     key-name "auto-key"]
         (let [target-env (first *environments*)]
           (api/ensure-env-exist target-env {:prior library})
           (ak/create {:name key-name
                       :description "my description"
                       :environment target-env})
           (provision/with-client "ak-link" ssh-conn
             (client/register ssh-conn
                              {:org "ACME_Corporation"
                               :activationkey key-name})
             (let [mysys (client/my-hostname ssh-conn)]
               (system/validate-activation-key-link mysys key-name)))))))

  (deftest "Install package group"
    :data-driven true
    :description "Add package and package group"
    :blockers rest/katello-only

    (fn [package-name]
      (let [target-env (first *environments*)
            system (uniqueify (kt/newSystem {:name "pkg_install", :env target-env}))
            product (configure-product-for-pkg-install target-env)]

        (provision/with-client (:name system)
          ssh-conn
          (client/register ssh-conn
                           {:username (:name *session-user*)
                            :password (:password *session-user*)
                            :org (-> product :provider :org :name)
                            :env (:name target-env)
                            :force true})
          (let [mysys (client/my-hostname ssh-conn)]
            (client/subscribe ssh-conn (system/pool-id system product))
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
                             {:username (:name *session-user*)
                              :password (:password *session-user*)
                              :org (-> env :org :name)
                              :env (:name env)
                              :force true})
            (assert/is (= {:name env} (system/environment mysys))))
          (assert/is (not= (:environment_id mysys)
                           (rest/get-id env-dev)))))))

  (deftest  "Registering a system from CLI and consuming contents from UI"
    (let [gpgkey (-> {:name "mykey", :org *session-org*,
                      :contents (slurp "http://inecas.fedorapeople.org/fakerepos/zoo/RPM-GPG-KEY-dummy-packages-generator" )}
                     kt/newGPGKey
                     uniqueify)
          repo (assoc (fresh-repo *session-org* "http://inecas.fedorapeople.org/fakerepos/zoo/") :gpg-key gpgkey)]
      (create-recursive repo)
      (when (rest/is-katello?)
        (changeset/sync-and-promote (list repo) (first *environments*)))
      (provision/with-client "consume-content"
        ssh-conn
        (client/register ssh-conn {:username (:name *session-user*)
                                   :password (:password *session-user*)
                                   :org (kt/org repo)
                                   :env (first *environments*)
                                   :force true})
        (let [mysys (client/my-hostname ssh-conn)
              product-name (-> repo kt/product :name)]
          (system/subscribe {:system-name mysys
                             :add-products product-name})
          (client/sm-cmd ssh-conn :refresh)
          (let [cmd (format "subscription-manager list --consumed | grep -o %s" product-name)
                result (client/run-cmd ssh-conn cmd)]
            (assert/is (->> result :exit-code (= 0))))))))

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
                         {:username (:name *session-user*)
                          :password (:password *session-user*)
                          :org (-> env-dev :org :name)
                          :env (:name env-dev)
                          :force true})
        (let [mysys (-> {:name (client/my-hostname ssh-conn)}
                        katello/newSystem
                        rest/read)]
          (assert/is (= (:name env-dev) (system/environment mysys)))
          (ui/update mysys assoc :env env-test)
          (assert/is (= (:name env-test) (system/environment mysys)))
          (client/subscribe ssh-conn (system/pool-id mysys product))
          (client/run-cmd ssh-conn "rpm --import http://inecas.fedorapeople.org/fakerepos/zoo/RPM-GPG-KEY-dummy-packages-generator")
          (client/sm-cmd ssh-conn :refresh)
          (client/run-cmd ssh-conn "yum repolist")
          (expecting-error [:type :katello.systems/package-install-failed]
                           (ui/update mysys update-in [:packages] (fnil conj #{}) package))
          (let [cmd_result (client/run-cmd ssh-conn "rpm -q cow")]
            (assert/is (->> cmd_result :exit-code (= 1)))))))))
