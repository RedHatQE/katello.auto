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
                     [sync-management :as sync]
                     [repositories :as repo]
                     [ui-common :as common]
                     [changesets :as changeset]
                     [tasks :refer :all]
                     [systems :as system]
                     [gpg-keys :as gpg-key]
                     [notices :as notices]
                     [conf :refer [*session-user* *session-org* config *environments*]]
                     [blockers :refer [bz-bugs auto-issue]])
            [katello.client.provision :as provision]
            [katello.tests.useful :refer [create-all-recursive create-series
                                          create-recursive fresh-repo]]
            [clojure.string :refer [blank? join]]
            [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            [test.tree.script :refer [defgroup deftest]]
            [clojure.zip :as zip]
            [slingshot.slingshot :refer [throw+]]
            [serializable.fn :refer [fn]]
            [test.assert :as assert]))

;; Functions

(defn create-test-environment []
  (def test-environment (first *environments*))
  (create-recursive test-environment))

(with-unique-ent "system" (kt/newSystem {:name "sys"
                                         :env test-environment}))
(defn register-new-test-system []
  (with-unique-system s
    (rest/create s)))

(defn verify-system-rename [system]
  (nav/go-to (ui/update system update-in [:name] uniqueify)))

(defn verify-system-appears-on-env-page
  [system]
  (nav/go-to ::system/named-by-environment-page system)
  (assert/is (= (:environment_id system)
                (-> test-environment rest/query :id))))

(defn validate-sys-subscription
  "Validate subscription tab when no subscription are attached to selected system"
  [system]
  (nav/go-to ::system/subscriptions-page system)
  (browser isElementPresent ::system/red-subs-icon)
  (assert/is (= "Subscriptions are not Current Details" (browser getText ::system/subs-text)))
  (assert/is (= "Auto-attach On, No Service Level Preference" (browser getText ::system/subs-servicelevel)))
  (assert/is (common/disabled? ::system/subs-attach-button)))

(defn configure-product-for-pkg-install
  "Creates and promotes a product with fake content repo, returns the
  product."
  [target-env]
  (with-unique [provider (katello/newProvider {:name "custom_provider" :org *session-org*})
                product (katello/newProduct {:name "fake" :provider provider})
                testkey (katello/newGPGKey {:name "mykey" :org *session-org*
                                            :contents (slurp
                                                       "http://inecas.fedorapeople.org/fakerepos/zoo/RPM-GPG-KEY-dummy-packages-generator")})
                repo (katello/newRepository {:name "zoo_repo"
                                             :product product
                                             :url "http://inecas.fedorapeople.org/fakerepos/zoo/"
                                             :gpg-key testkey})]
    (create-recursive target-env)
    (ui/create-all (list testkey provider product repo))
    (when (rest/is-katello?)
      (changeset/sync-and-promote (list repo) target-env))
    product))

(defn validate-system-facts
  [system cpu arch virt? env]
  (nav/go-to ::system/facts-page system)
  (browser click ::system/cpu-expander)
  (assert/is (= cpu (browser getText ::system/cpu-socket)))
  (browser click ::system/network-expander)
  (assert/is (= (:name system) (browser getText ::system/net-hostname)))
  (browser click ::system/uname-expander)
  (assert/is (= arch (browser getText ::system/machine-arch)))
  (browser click ::system/virt-expander)
  (if virt?
    (assert/is (= "true" (browser getText ::system/virt-status)))
    (assert/is (= "false" (browser getText ::system/virt-status))))
  (let [details (system/get-details system)]
    (assert/is (= (:name system) (details "Name")))
    (assert/is (= arch (details "Arch")))
    (assert/is (= (:name env)  (details "Environment")))))

(def save-cancel
  (partial #'common/save-cancel
           ::system/save-button ::system/cancel-button :sys-update))

(defn ui-count-systems "Gets the total count of systems in the given org"
  [org]
  (nav/go-to ::system/page org)
  (Integer/parseInt (browser getText ::system/total-sys-count)))

;; Tests

(def success #(-> % :type (= :success)))

(defgroup system-tests
  :group-setup create-test-environment
  :blockers (bz-bugs "717408" "728357")

  (deftest "Rename an existing system"
    :uuid "50895adf-ae72-5dd4-bd1b-1baf59fd0633"
    :blockers (bz-bugs "729364")
    (verify-system-rename (register-new-test-system)))

  (deftest "System details: save or cancel editing field"
    :uuid "b3f26238-b35c-aa84-3533-e3d3bb27bd8b"
    :data-driven true
    ;; blockers (bz-bugs "917033")

    (fn [input-loc new-value save? expected-res]
      (with-unique-system s
        (rest/create s)
        (expecting-error expected-res
                         (nav/go-to ::system/details-page s)
                         (save-cancel input-loc new-value save?))))

    [[::system/name-text-edit "yoursys" false success]
     [::system/name-text-edit "test.pnq.redhat.com" true success]
     [::system/name-text-edit (random-ascii-string 251) true (common/errtype ::notification/system-name-char-limit)]
     [::system/name-text-edit (random-ascii-string 250) true success]
     [::system/description-text-edit "cancel description" false success]
     [::system/description-text-edit "System Registration Info" true success]
     [::system/description-text-edit (random-ascii-string 256) true (common/errtype ::notification/sys-description-255-char-limit)]
     [::system/description-text-edit (random-ascii-string 255) true success]
     [::system/location-text-edit "Cancel Location" false success]
     [::system/location-text-edit "System Location Info" true success]
     [::system/location-text-edit (random-ascii-string 256) true (common/errtype ::notification/sys-location-255-char-limit)]
     [::system/location-text-edit (random-ascii-string 255) true success]])


  (deftest "Verify system appears on Systems By Environment page in its proper environment"
    :uuid "f7d6189a-6033-f434-203b-dc6f700e3f15"
    :blockers (conj (bz-bugs "738054") rest/katello-only)
    (verify-system-appears-on-env-page (register-new-test-system)))

  (deftest "Subscribe a system to a custom product"
    :uuid "5b2feb1c-ce47-fcd4-fdf3-f4205b8e75d2"
    :blockers (conj (bz-bugs "733780" "736547" "784701") rest/katello-only)

    (with-unique [provider (katello/newProvider {:name "subscr-prov" :org *session-org*})
                  product (katello/newProduct {:name "subscribe-me"
                                               :provider provider})]
      (rest/create provider)
      (rest/create product)
      (ui/update (register-new-test-system) assoc :products (list product))))

  (deftest "Set a system to autosubscribe with no SLA preference"
    :uuid "18ea0330-2d2a-7f14-054b-52c166070840"
    :blockers (bz-bugs "845261")
    (ui/update (register-new-test-system) assoc
               :auto-attach true
               :service-level "No Service Level Preference"))

  (deftest "Remove System"
    :uuid "54887f50-0bb7-dea4-93ab-a326a61a3c80"
    (ui/delete (register-new-test-system)))

  (deftest "Remove multiple systems"
    :uuid "3aaf62ed-c802-aa04-1503-d5c4de3939fb"
    (let [systems (->> {:name "mysys"
                        :sockets "1"
                        :system-arch "x86_64"
                        :env test-environment} katello/newSystem uniques (take 3))]
      (rest/create-all systems)
      (system/multi-delete systems)))

  (deftest "Remove systems and validate sys-count"
    :uuid "ad9ea75b-9dbe-0ca4-89db-510babd14234"
    (with-unique [org (kt/newOrganization {:name "delsyscount"
                                           :initial-env (kt/newEnvironment {:name "dev"})})]
      (let [systems (->> {:name "delsys", :env (:initial-env org)}
                         kt/newSystem
                         uniques
                         (take 4))]
        (create-all-recursive systems)
        (assert/is (= (count systems) (ui-count-systems org)))
        (ui/delete (first systems))
        (assert/is (= (dec (count systems)) (ui-count-systems org)))
        (system/multi-delete (rest systems))
        (assert/is (= 0 (ui-count-systems org))))))

  (deftest "Remove System: with yes-no confirmation"
    :uuid "5773a3eb-3266-9ad4-ac4b-6a9fba143ba3"
    :data-driven true

    (fn [confirm?]
      (with-unique [system (kt/newSystem {:name "mysystem"
                                          :sockets "1"
                                          :system-arch "x86_64"
                                          :env test-environment})]
        (rest/create system)
        (nav/go-to system)
        (browser click ::system/remove)
        (if confirm?
          (do (browser click ::ui/confirmation-yes)
              (notification/success-type :sys-destroy)
              (assert (rest/not-exists? system)))
          (do (browser click ::ui/confirmation-no)
              (nav/go-to system)))))
    [[false]
     [true]])

  (deftest "Creates org with default custom system key and adds new system"
    :uuid "7d5ff301-b2eb-05a4-aee3-ab60d9583585"
    :blockers (list rest/katello-only)
    (with-unique [org (kt/newOrganization
                       {:name "defaultsysinfo"
                        :initial-env (kt/newEnvironment {:name "dev"})})

                  system (kt/newSystem {:name "sys"
                                        :sockets "1"
                                        :system-arch "x86_64"
                                        :env (:initial-env org)})]
      (ui/create org)
      (org/add-custom-keyname org ::org/system-default-info-page "Manager")
      (rest/create system)
      (nav/go-to ::system/custom-info-page system)
      (assert/is (browser isTextPresent "Manager"))))

  (deftest "Creates org adds new system then applies custom org default"
    :uuid "0825248e-3c30-5194-28b3-eeff22bb5806"
    (with-unique [org (kt/newOrganization {:name "defaultsysinfo"})
                  system (kt/newSystem {:name "sys"
                                        :sockets "1"
                                        :system-arch "x86_64"})]
      (let [sys1 (assoc system :env (kt/newEnvironment {:name "Library" :org org}))]
        (rest/create-all (list org sys1))
        (nav/go-to sys1)
        (browser click ::system/custom-info)
        (assert/is (not (org/isKeynamePresent? "fizzbuzz")))
        (org/add-custom-keyname org ::org/system-default-info-page "fizzbuzz" {:apply-default true})
        (nav/go-to sys1)
        (browser click ::system/custom-info)
        (assert/is (org/isKeynamePresent? "fizzbuzz")))))

  (deftest "System Details: Add custom info"
    :uuid "d4543bef-3b65-87b4-de1b-791e634d494a"
    :blockers (bz-bugs "919373")
    (with-unique-system s
      (rest/create s)
      (ui/update s assoc :custom-info {"Hypervisor" "KVM"})))

  (deftest "System Details: Update custom info"
    :uuid "24ea3405-34cc-0b84-20fb-5d4794c5b47b"
    :blockers (bz-bugs "919373" "970079")
    (with-unique-system s
      (rest/create s)
      (let [s (ui/update s assoc :custom-info {"Hypervisor" "KVM"})]
        (ui/update s assoc :custom-info {"Hypervisor" "Xen"}))))

  (deftest "Remove systems and validate sys-count"
    :uuid "0ddac55e-1b1d-7d94-8b9b-c819b4ea7936"
    (with-unique [org (kt/newOrganization {:name "delsyscount"
                                           :initial-env (kt/newEnvironment {:name "dev"})})]
      (let [systems (->> {:name "delsys", :env (:initial-env org)}
                         kt/newSystem
                         uniques
                         (take 4))]
        (create-all-recursive systems)
        (assert/is (= (count systems) (ui-count-systems org)))
        (ui/delete (first systems))
        (assert/is (= (dec (count systems)) (ui-count-systems org)))
        (system/multi-delete (rest systems))
        (assert/is (= 0 (ui-count-systems org))))))

  (deftest "System Details: Add custom info"
    :uuid "577a48a3-6a8e-1324-c8a3-71c959b7f373"
    :blockers (bz-bugs "919373")
    :data-driven true

    (fn [keyname custom-value success?]
      (with-unique-system s
        (rest/create s)
        (ui/update s assoc :custom-info {keyname custom-value})
        (assert/is (= (browser isTextPresent keyname) success?))))

    [["Hypervisor" "KVM" true]
     [(random-ascii-string 255) (uniqueify "cust-value") true]
     [(uniqueify "cust-keyname") (random-ascii-string 255) true]
     [(uniqueify "cust-keyname") (random-ascii-string 256) false]
     [(random-unicode-string 10) (uniqueify "cust-value") true]
     [(uniqueify "cust-keyname") (random-unicode-string 10) true]

     (with-meta
       ["foo@!#$%^&*()" "bar_+{}|\"?<blink>hi</blink>" true]
       {:blockers (bz-bugs "951231")})

     ["foo@!#$%^&*()" "bar_+{}|\"?hi" true]])

  (deftest "System Details: Update custom info"
    :uuid "fd2edd3a-3653-9544-c26b-1c9b4b9ef9d7"
    :blockers (bz-bugs "919373" "951231" "951197" "970079")
    :data-driven true

    (fn [keyname custom-value new-value success?]
      (with-unique-system s
        (rest/create s)
        (let [s (ui/update s assoc :custom-info {keyname custom-value})]
          (assert/is (browser isTextPresent custom-value))
          (ui/update s assoc :custom-info {keyname new-value})
          (assert/is (= (browser isTextPresent new-value) success?)))))

    [["Hypervisor" "KVM" "Xen" true]
     ["Hypervisor" "KVM" (random-ascii-string 255) true]
     ["Hypervisor" "KVM" (random-ascii-string 256) false]
     ["Hypervisor" "KVM" (random-unicode-string 10) true]
     ["Hypervisor" "KVM" "bar_+{}|\"?<blink>hi</blink>" true]])

  (deftest "System Details: Delete custom info"
    :uuid "b3b7de8e-cf55-1b24-346b-bab3bc209660"
    :blockers (bz-bugs "919373")
    (with-unique-system s
      (rest/create s)
      (let [s (ui/update s assoc :custom-info {"Hypervisor" "KVM"})]
        (assert/is (browser isTextPresent "Hypervisor"))
        (ui/update s update-in [:custom-info] dissoc "Hypervisor"))))

  (deftest "Check whether all the envs of org can be selected for a system"
    :uuid "8284f1df-c3d7-0b94-a583-bf702470b485"
    :blockers (list rest/katello-only)
    (let [arch "x86_64"
          cpu "2"
          org       (uniqueify (kt/newOrganization {:name "defaultsysinfo"}))
          env-chain (take 3 (uniques (katello/newEnvironment {:name "envs", :org org})))
          system    (uniqueify (kt/newSystem {:name "mysystem"
                                              :env (first env-chain)
                                              :sockets cpu
                                              :system-arch arch
                                              :virtual? false}))]
      (rest/create-all (concat (list org) (kt/chain env-chain) (list system)))
      (ui/update system assoc :env (second env-chain))
      (ui/update system assoc :env (last env-chain))))

  (deftest "Check whether the details of registered system are correctly displayed in the UI"
    :uuid "21db8829-8208-ff54-63eb-40e3ce4d39db"
    :blockers (bz-bugs "959211")
    (provision/with-queued-client
      ssh-conn
      (client/register ssh-conn
                       {:username (:name *session-user*)
                        :password (:password *session-user*)
                        :org (:name *session-org*)
                        :env (:name test-environment)
                        :force true})
      (let [hostname (client/my-hostname ssh-conn)
            system (kt/newSystem {:name hostname
                                  :env test-environment})
            details (system/get-details system)]
        (assert/is (= (client/get-distro ssh-conn)
                      (details "OS")))
        (assert/is (every? not-empty (vals details)))
        (assert/is (= (client/get-ip-address ssh-conn)
                      (system/get-ip-addr system))))))

  (deftest "Review Facts of registered system"
    :uuid "191d75c4-860f-62a4-908b-659ad8acdc4f"
    ;;:blockers no-clients-defined
    :blockers (bz-bugs "959211" "970570")
    (provision/with-queued-client
      ssh-conn
      (client/register ssh-conn {:username (:name *session-user*)
                                 :password (:password *session-user*)
                                 :org (:name *session-org*)
                                 :env (:name test-environment)
                                 :force true})
      (let [hostname (client/my-hostname ssh-conn)
            system (kt/newSystem {:name hostname
                                  :env test-environment})
            facts (system/get-facts system)]
        (system/expand-collapse-facts-group system)
        (assert/is (every? (complement empty?) (vals facts))))))


  (deftest "System-Details: Validate Activation-key link"
    :uuid "0f8a619c-f2f1-44f4-4ad3-84379abbfa8c"
    :blockers (bz-bugs "959211")

    (with-unique [ak (kt/newActivationKey {:name "ak-link"
                                           :env test-environment})]
      (ui/create ak)
      (provision/with-queued-client ssh-conn
        (client/register ssh-conn
                         {:org (:name *session-org*)
                          :activationkey (:name ak)})
        (let [system (kt/newSystem {:name (client/my-hostname ssh-conn) :env test-environment})
              aklink (system/activation-key-link (:name ak))]
          (nav/go-to ::system/details-page system)
          (when (browser isElementPresent aklink)
            (browser clickAndWait aklink))))))

  (deftest "Install package group"
    :uuid "869db0f1-3e41-b864-eecb-1acda7f6daf7"
    :data-driven true
    :description "Add package and package group"
    :blockers (conj (bz-bugs "959211" "970570")
                    rest/katello-only
                    (auto-issue "790"))

    (fn [package-opts]
      (let [target-env test-environment
            product (configure-product-for-pkg-install target-env)]
        (provision/with-queued-client
          ssh-conn
          (client/register ssh-conn
                           {:username (:name *session-user*)
                            :password (:password *session-user*)
                            :org (-> product :provider :org :name)
                            :env (:name target-env)
                            :force true})
          (let [mysys (-> {:name (client/my-hostname ssh-conn) :env target-env}
                          katello/newSystem)]
            (client/subscribe ssh-conn (system/pool-id mysys product))
            (client/run-cmd ssh-conn "rpm --import http://inecas.fedorapeople.org/fakerepos/zoo/RPM-GPG-KEY-dummy-packages-generator")
            (system/add-package mysys package-opts)))))

    [[{:package "cow"}]
     [{:package-group "birds"}]])

  (deftest "Re-registering a system to different environment"
    :uuid "72dfb70e-51c5-b074-4beb-7def65550535"
    :blockers (conj (bz-bugs "959211") rest/katello-only)

    (let [[env-dev env-test :as envs] (->> {:name "env" :org *session-org*}
                                           katello/newEnvironment
                                           create-series
                                           (take 2))]
      (provision/with-queued-client
        ssh-conn
        (let [hostname (client/my-hostname ssh-conn)
              mysys (kt/newSystem {:name hostname :env env-dev})]
          (doseq [env [env-dev env-test]]
            (client/register ssh-conn
                             {:username (:name *session-user*)
                              :password (:password *session-user*)
                              :org (-> env :org :name)
                              :env (:name env)
                              :force true})
            (assert/is (= (:name env) (system/environment mysys))))
          (assert/is (not= (:environment_id mysys)
                           (rest/get-id env-dev)))))))

  (deftest "Register a system and validate subscription tab"
    :uuid "7169755a-379a-9e24-37eb-cf222e6beb86"
    :blockers (list rest/katello-only)
    (with-unique [target-env (kt/newEnvironment {:name "dev"
                                                 :org *session-org*})
                  repo (fresh-repo *session-org* "http://inecas.fedorapeople.org/fakerepos/zoo/")]
      (ui/create target-env)
      (create-recursive repo)
      (sync/perform-sync (list repo))
      (provision/with-queued-client
        ssh-conn
        (client/register ssh-conn
                         {:username (:name *session-user*)
                          :password (:password *session-user*)
                          :org (:name *session-org*)
                          :env (:name target-env)
                          :force true})
        (let [hostname (client/my-hostname ssh-conn)
              system (kt/newSystem {:name hostname :env target-env})]
          (validate-sys-subscription system)))))

  (deftest "Register a system using multiple activation keys"
    :uuid "a39bf0f7-7e7b-1e54-cdf3-d1442d6e6a6a"
    :blockers (list rest/katello-only)
    (with-unique [target-env (kt/newEnvironment {:name "dev" :org *session-org*})
                  [ak1 ak2] (kt/newActivationKey {:name "ak1"
                                                  :env target-env
                                                  :description "auto activation key"})]
      (ui/create-all (list target-env ak1 ak2))
      (let [ak1-name (:name ak1)
            ak2-name (:name ak2)
            ak-name (join "," [ak1-name ak2-name])]
        (provision/with-queued-client ssh-conn
          (client/register ssh-conn
                           {:org (:name *session-org*)
                            :activationkey ak-name})
          (let [system (kt/newSystem {:name (client/my-hostname ssh-conn) :env target-env})]
            (doseq [ak [ak1 ak2]]
              (let [aklink (system/activation-key-link (:name ak))]
                (nav/go-to ::system/details-page system)
                (when (browser isElementPresent aklink)
                  (browser clickAndWait aklink)))))))))

  (deftest  "Registering a system from CLI and consuming contents from UI"
    :uuid "867f7827-2ec2-48b4-d063-adc1e58dcfe5"
    :blockers (conj (bz-bugs "959211") rest/katello-only)

    (let [gpgkey (-> {:name "mykey", :org *session-org*,
                      :contents (slurp "http://inecas.fedorapeople.org/fakerepos/zoo/RPM-GPG-KEY-dummy-packages-generator" )}
                     kt/newGPGKey
                     uniqueify)
          repo (assoc (fresh-repo *session-org* "http://inecas.fedorapeople.org/fakerepos/zoo/") :gpg-key gpgkey)]
      (create-recursive repo)
      (when (rest/is-katello?)
        (changeset/sync-and-promote (list repo) test-environment))
      (provision/with-queued-client
        ssh-conn
        (client/register ssh-conn {:username (:name *session-user*)
                                   :password (:password *session-user*)
                                   :org (:name *session-org*)
                                   :env (:name test-environment)
                                   :force true})
        (let [mysys (kt/newSystem {:name (client/my-hostname ssh-conn) :env test-environment})
              product-name (-> repo kt/product :name)]
          (ui/update mysys assoc :products (list (kt/product repo)))
          (client/sm-cmd ssh-conn :refresh)
          (let [cmd (format "subscription-manager list --consumed | grep -o %s" product-name)
                result (client/run-cmd ssh-conn cmd)]
            (assert/is (->> result :exit-code (= 0))))))))

  (deftest "Install package after moving a system from one env to other"
    :uuid "960cc577-e045-f9d4-7383-dec4e5eed00b"
    :blockers (conj (bz-bugs "959211" "970570")
                    rest/katello-only
                    (auto-issue "791"))

    (let [[env-dev env-test :as envs] (->> {:name "env" :org *session-org*}
                                           katello/newEnvironment
                                           create-series
                                           (take 2))
          product (configure-product-for-pkg-install env-dev)
          package (katello/newPackage {:name "cow" :product product})]
      (provision/with-queued-client
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
