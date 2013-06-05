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
                     [conf :refer [*session-user* *session-org* config *environments*]])
            [katello.client.provision :as provision]
            [katello.tests.useful :refer [create-all-recursive create-series
                                          create-recursive fresh-repo]]
            [clojure.string :refer [blank? join]]
            [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            (test.tree [script :refer [defgroup deftest]]
                       [builder :refer [union]])
            [clojure.zip :as zip]
            [slingshot.slingshot :refer [throw+]]
            [serializable.fn :refer [fn]]
            [test.assert :as assert]
            [bugzilla.checker :refer [open-bz-bugs]]))




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
  (assert/is (common/disabled? (browser getAttribute ::system/subs-attach-button))))
    
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

(defn verify-new-system-tooltip
  "Confirms that tooltips in the New System form appear and show the correct messages"
  [icon-locator expected-text]
  (nav/go-to ::system/new-page *session-org*)
  (browser mouseOver icon-locator)
  (Thread/sleep 2000)
  (assert/is (browser isTextPresent expected-text))
  (browser mouseOut icon-locator)
  (Thread/sleep 2000)
  (assert/is (not (browser isTextPresent expected-text))))


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

(defn edit-sysname
  "Edits system"
  [system new-name save?]
  (nav/go-to ::system/details-page system)
  (let [name-edit-field (common/inactive-edit-field ::system/name-text-edit)
        old-name (browser getText name-edit-field)]
    (browser click name-edit-field)
    (browser setText ::system/name-text-edit new-name)
    (if save?
      (do (browser click ::system/save-button)
          (notification/check-for-success {:match-pred (notification/request-type? :sys-update)})
          (when-not (= new-name (browser getText name-edit-field))
            (throw+ {:type ::system/sysname-not-edited
                     :msg "Still getting old system name."})))
      (do (browser click ::system/cancel-button)
          (when-not (= (:name system) old-name)
            (throw+ {:type ::system/sysname-edited-anyway
                     :msg "System system changed even after clicking cancel button."}))))))

(defn edit-sys-description
  "Edit description of selected system"
  [system new-description save?]
  (nav/go-to ::system/details-page system)
  (let [desc-edit-field (common/inactive-edit-field ::system/description-text-edit)
        original-description (browser getText desc-edit-field)]
    (browser click desc-edit-field)
    (browser setText ::system/description-text-edit new-description)
    (if save?
      (do (browser click ::system/save-button)
          (notification/check-for-success {:match-pred (notification/request-type? :sys-update)})
          (when-not (= new-description (browser getText desc-edit-field))
            (throw+ {:type ::system/sys-description-not-edited
                     :msg "Still getting old description of selected system."})))
      (do (browser click ::system/cancel-button)
          (when-not (= original-description (browser getText desc-edit-field))
            (throw+ {:type ::system/sys-description-edited-anyway
                     :msg "System description changed even after clicking cancel button."}))))))

(defn edit-sys-location
  "Edit location of selected system"
  [system new-location save?]
  (nav/go-to ::system/details-page system)
  (let [loc-edit-field (common/inactive-edit-field ::system/location-text-edit)
        original-location (browser getText loc-edit-field)]
    (browser click loc-edit-field)
    (browser setText ::system/location-text-edit new-location)
    (if save?
      (do (browser click ::system/save-button)
          (notification/check-for-success {:match-pred (notification/request-type? :sys-update)})
          (when-not (= new-location (browser getText loc-edit-field))
            (throw+ {:type ::system/sys-location-not-edited
                     :msg "Still getting old location of selected system."})))
      (do (browser click ::system/cancel-button)
          (when-not (= original-location (browser getText loc-edit-field))
            (throw+ {:type ::system/sys-location-edited-anyway
                     :msg "System location changed even after clicking cancel button."}))))))

(defn ui-count-systems "Gets the total count of systems in the given org"
  [org]
  (nav/go-to ::system/page org)
  (Integer/parseInt (browser getText ::system/total-sys-count)))

;; Tests

(let [success #(-> % :type (= :success))]
  
  (defgroup system-tests
    :group-setup create-test-environment
    :blockers (open-bz-bugs "717408" "728357")

    (deftest "Rename an existing system"
      :blockers (open-bz-bugs "729364")
      (verify-system-rename (register-new-test-system)))

    (deftest "System-details: Edit system"
      :data-driven true
      ;; blockers (open-bz-bugs "917033")

      (fn [new-system-name save? expected-res]
        (with-unique-system s
          (rest/create s)
          (expecting-error expected-res (edit-sysname s new-system-name save?))))

      [["yoursys" false success]
       ["test.pnq.redhat.com" true success]
       [(random-string (int \a) (int \z) 251) true (common/errtype ::notification/system-name-char-limit)]
       [(random-string (int \a) (int \z) 250) true success]])

    (deftest "System-details: Edit Description"
      :data-driven true

      (fn [new-description save? expected-res]
        (with-unique-system s
          (rest/create s)
          (expecting-error expected-res (edit-sys-description s new-description save?))))

      [["cancel description" false success]
       ["System Registration Info" true success]
       [(random-string (int \a) (int \z) 256) true (common/errtype ::notification/sys-description-255-char-limit)]
       [(random-string (int \a) (int \z) 255) true success]])
    
    (deftest "System-details: Edit Location"
      :data-driven true

      (fn [new-location save? expected-res]
        (with-unique-system s
          (rest/create s)
          (expecting-error expected-res (edit-sys-location s new-location save?))))

      [["Cancel Location" false success]
       ["System Location Info" true success]
       [(random-string (int \a) (int \z) 256) true (common/errtype ::notification/sys-location-255-char-limit)]
       [(random-string (int \a) (int \z) 255) true success]])

    (deftest "Verify system appears on Systems By Environment page in its proper environment"
      :blockers (union rest/katello-only (open-bz-bugs "738054"))
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
                (notification/check-for-success {:match-pred (notification/request-type? :sys-destroy)})
                (assert (rest/not-exists? system)))
            (do (browser click ::ui/confirmation-no)
                (nav/go-to system)))))
      [[false]
       [true]])
    
    (deftest "Creates org with default custom system key and adds new system"
      :blockers rest/katello-only
      (with-unique [org (kt/newOrganization
                         {:name "defaultsysinfo"
                          :initial-env (kt/newEnvironment {:name "dev"})})
                     
                    system (kt/newSystem {:name "sys"
                                          :sockets "1"
                                          :system-arch "x86_64"
                                          :env (:initial-env org)})]
        (ui/create org)
        (org/add-custom-keyname org ::org/system-default-info-page "Manager")
        (ui/create system)
        (browser click ::system/custom-info)
        (assert/is (browser isTextPresent "Manager"))))

    (deftest "Creates org adds new system then applies custom org default"
      (with-unique [org (kt/newOrganization {:name "defaultsysinfo"})
                    system (kt/newSystem {:name "sys"
                                          :sockets "1"
                                          :system-arch "x86_64"})]
        (let [sys1 (assoc system :env (kt/newEnvironment {:name "Library" :org org}))]
          (ui/create org)
          (rest/create sys1)
          (nav/go-to sys1)
          (browser click ::system/custom-info)
          (assert/is (not (browser isTextPresent "fizzbuzz")))
          (org/add-custom-keyname org ::org/system-default-info-page "fizzbuzz" {:apply-default true})
          (nav/go-to sys1)
          (browser click ::system/custom-info)
          (assert/is (browser isTextPresent "fizzbuzz")))))

    (deftest "System Details: Add custom info"
      :blockers (open-bz-bugs "919373")
      (with-unique-system s
        (rest/create s)
        (ui/update s assoc :custom-info {"Hypervisor" "KVM"})))
    
    (deftest "System Details: Update custom info"
      :blockers (open-bz-bugs "919373" "970079")
      (with-unique-system s
        (rest/create s)
        (let [s (ui/update s assoc :custom-info {"Hypervisor" "KVM"})]
          (ui/update s assoc :custom-info {"Hypervisor" "Xen"}))))

    (deftest "Remove systems and validate sys-count"
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
      :data-driven true

      (fn [confirm?]
        (with-unique-system s
          (rest/create s)
          (nav/go-to s)
          (browser click ::system/remove)
          (if confirm?
            (do (browser click ::ui/confirmation-yes)
                (notification/check-for-success {:match-pred (notification/request-type? :sys-destroy)})
                (assert (rest/not-exists? s)))
            (do (browser click ::system/confirm-to-no)
                (nav/go-to s)))))
      [[false]
       [true]])

    (deftest "System Details: Add custom info"
      :blockers (union (open-bz-bugs "919373")
                       (fn [{:keys [test-zipper]}] ;; skip if this bug is open and we're expecting failure
                         (or (and (-> test-zipper zip/node :parameters last not) ;; success? == false
                                  ((open-bz-bugs "951197") nil))
                             (list))))
      :data-driven true

      (fn [keyname custom-value success?]
        (with-unique-system s
          (rest/create s)
          (ui/update s assoc :custom-info {keyname custom-value})
          (assert/is (= (browser isTextPresent keyname) success?))))

      [["Hypervisor" "KVM" true]
       [(random-string (int \a) (int \z) 255) (uniqueify "cust-value") true]
       [(uniqueify "cust-keyname") (random-string (int \a) (int \z) 255) true]
       [(uniqueify "cust-keyname") (random-string (int \a) (int \z) 256) false]
       [(random-string 0x0080 0x5363 10) (uniqueify "cust-value") true]
       [(uniqueify "cust-keyname") (random-string 0x0080 0x5363 10) true]

       (with-meta
         ["foo@!#$%^&*()" "bar_+{}|\"?<blink>hi</blink>" false]
         {:blockers (open-bz-bugs "951231")})

       ["foo@!#$%^&*()" "bar_+{}|\"?hi" true]])

    (deftest "System Details: Update custom info"
      :blockers (open-bz-bugs "919373" "951231" "951197" "970079")
      :data-driven true

      (fn [keyname custom-value new-value success?]
        (with-unique-system s
          (rest/create s)
          (let [s (ui/update s assoc :custom-info {keyname custom-value})]
            (assert/is (browser isTextPresent custom-value))
            (ui/update s assoc :custom-info {keyname new-value})
            (assert/is (= (browser isTextPresent new-value) success?)))))

      [["Hypervisor" "KVM" "Xen" true]
       ["Hypervisor" "KVM" (random-string (int \a) (int \z) 255) true]
       ["Hypervisor" "KVM" (random-string (int \a) (int \z) 256) false]
       ["Hypervisor" "KVM" (random-string 0x0080 0x5363 10) true]
       ["Hypervisor" "KVM" "bar_+{}|\"?<blink>hi</blink>" false]])

    (deftest "System Details: Delete custom info"
      :blockers (open-bz-bugs "919373")
      (with-unique-system s
        (rest/create s)
        (let [s (ui/update s assoc :custom-info {"Hypervisor" "KVM"})]
          (assert/is (browser isTextPresent "Hypervisor"))
          (ui/update s update-in [:custom-info] dissoc "Hypervisor"))))

    (deftest "System name is required when creating a system"
      :blockers rest/katello-only
      (expecting-error val/name-field-required
                       (ui/create (kt/newSystem {:name ""
                                                 :facts (system/random-facts)
                                                 :env test-environment}))))

    (deftest "New System Form: tooltips pop-up with correct information"
      :blockers rest/katello-only
      :data-driven true
      verify-new-system-tooltip
      [[::system/ram-icon "The amount of RAM memory, in gigabytes (GB), which this system has"]
       [::system/sockets-icon "The number of CPU Sockets or LPARs which this system uses"]])
    ;; FIXME - convert-to-records

  

    (deftest "Add system from UI"
      :blockers rest/katello-only
      :data-driven true
      (fn [virt?]
        (let [arch "x86_64", cpu "2"]
          (with-unique [system (kt/newSystem {:name "mysystem"
                                              :env test-environment
                                              :sockets cpu
                                              :system-arch arch
                                              :virtual? virt?})]
            (ui/create system)
            (validate-system-facts system cpu arch virt? test-environment))))

      [[false]
       [true]])
    
    (deftest "Check whether all the envs of org can be selected for a system"
      :blockers rest/katello-only
      (let [arch "x86_64"
            cpu "2"
            org       (uniqueify (kt/newOrganization {:name "defaultsysinfo"}))
            env-chain (take 3 (uniques (katello/newEnvironment {:name "envs", :org org})))
            system    (uniqueify (kt/newSystem {:name "mysystem"
                                               :env (first env-chain)
                                               :sockets cpu
                                               :system-arch arch
                                               :virtual? false}))]
        (ui/create-all (concat (list org) (kt/chain env-chain) (list system)))
        (ui/update system assoc :env (second env-chain))
        (ui/update system assoc :env (last env-chain))))

    (deftest "Check whether the details of registered system are correctly displayed in the UI"
      :blockers (open-bz-bugs "959211")
      (provision/with-client "sys-detail"
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
      ;;:blockers no-clients-defined
      :blockers (open-bz-bugs "959211" "970570")
      (provision/with-client "sys-facts"
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
      :blockers (open-bz-bugs "959211")
      
      (with-unique [ak (kt/newActivationKey {:name "ak-link"
                                             :env test-environment})]
        (ui/create ak)
        (provision/with-client "ak-link" ssh-conn
          (client/register ssh-conn
                           {:org (:name *session-org*)
                            :activationkey (:name ak)})
          (let [system (kt/newSystem {:name (client/my-hostname ssh-conn) :env test-environment})
                aklink (system/activation-key-link (:name ak))]
            (nav/go-to ::system/details-page system)
            (when (browser isElementPresent aklink)
              (browser clickAndWait aklink))))))

    (deftest "Install package group"
      :data-driven true
      :description "Add package and package group"
      :blockers (union rest/katello-only (open-bz-bugs "959211" "970570"))

      (fn [package-opts]
        (let [target-env test-environment
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
              (system/add-package mysys package-opts)))))

      [[{:package "cow"}]
       [{:package-group "birds"}]])

    (deftest "Re-registering a system to different environment"
      :blockers (union rest/katello-only (open-bz-bugs "959211"))
      
      (let [[env-dev env-test :as envs] (->> {:name "env" :org *session-org*}
                                             katello/newEnvironment
                                             create-series
                                             (take 2))]
        (provision/with-client "reg-with-env-change"
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
      :blockers rest/katello-only
      (with-unique [target-env (kt/newEnvironment {:name "dev" 
                                                   :org *session-org*})
                    repo (fresh-repo *session-org* "http://inecas.fedorapeople.org/fakerepos/zoo/")]
        (ui/create target-env)
        (create-recursive repo)
        (sync/perform-sync (list repo))
        (provision/with-client "subs-tab"
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
      (with-unique [target-env (kt/newEnvironment {:name "dev" :org *session-org*})
                    [ak1 ak2] (kt/newActivationKey {:name "ak1"
                                              :env target-env
                                              :description "auto activation key"})]
        (ui/create-all (list target-env ak1 ak2))
        (let [ak1-name (:name ak1)
              ak2-name (:name ak2)
              ak-name (join "," [ak1-name ak2-name])]
          (provision/with-client "register-with-multi-ak" ssh-conn
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
      :blockers (open-bz-bugs "959211")
      
      (let [gpgkey (-> {:name "mykey", :org *session-org*,
                        :contents (slurp "http://inecas.fedorapeople.org/fakerepos/zoo/RPM-GPG-KEY-dummy-packages-generator" )}
                       kt/newGPGKey
                       uniqueify)
            repo (assoc (fresh-repo *session-org* "http://inecas.fedorapeople.org/fakerepos/zoo/") :gpg-key gpgkey)]
        (create-recursive repo)
        (when (rest/is-katello?)
          (changeset/sync-and-promote (list repo) test-environment))
        (provision/with-client "consume-content"
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
      :blockers (union rest/katello-only (open-bz-bugs "959211" "970570"))
      
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
              (assert/is (->> cmd_result :exit-code (= 1))))))))))
