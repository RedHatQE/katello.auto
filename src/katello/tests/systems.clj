(ns katello.tests.systems
  (:refer-clojure :exclude [fn])
  (:require [katello :as kt]
            (katello [navigation :as nav]
                     [notifications :as notification]
                     [ui :as ui]
                     [rest :as rest]
                     [manifest :as manifest]
                     [validation :as val]
                     [fake-content    :as fake]
                     [organizations :as org]
                     [environments :as env]
                     [client :as client]
                     [providers :as provider]
                     [sync-management :as sync]
                     [repositories :as repo]
                     [ui-common :as common]
                     [changesets :as changeset]
                     [redhat-repositories :as rh-repos]
                     [content-view-definitions :as views]
                     [tasks :refer :all]
                     [systems :as system]
                     [gpg-keys :as gpg-key]
                     [notices :as notices]
                     [conf :refer [*session-user* *session-org* config *environments*]]
                     [blockers :refer [bz-bugs bz-bug auto-issue]])
            [katello.client.provision :as provision]
            [katello.tests.content-views :refer [promote-published-content-view]]
            [katello.tests.useful :refer [create-all-recursive create-series
                                          create-recursive fresh-repo upload-manifest-with-rhel-subscriptions]]
            [clojure.string :refer [blank? join split]]
            [clj-webdriver.taxi :as browser]
            [webdriver :as wd]
            [test.tree.script :refer [defgroup deftest]]
            [clojure.zip :as zip]
            [slingshot.slingshot :refer [throw+]]
            [serializable.fn :refer [fn]]
            [test.assert :as assert]))

;; Functions

(def inputformat (java.text.SimpleDateFormat. "EEE MMM d HH:mm:ss zzz yyyy"))
(def outputformat (java.text.SimpleDateFormat. "MM/d/yy"))
(defn date [d] (.format outputformat (.parse inputformat d)))

(defn create-test-environment []
  (def test-environment (kt/library *session-org*)))

(with-unique-ent "system" (kt/newSystem {:name "sys"
                                         :env test-environment}))
(defn register-new-test-system []
  (with-unique-system s
    (rest/create s)))

(defn verify-system-rename [system]
  (nav/go-to (ui/update system update-in [:name] uniqueify)))

(defn validate-sys-subscription
  "Validate subscription tab when no subscription are attached to selected system"
  [system]
  (nav/go-to ::system/subscriptions-page system)
  (browser/exists?  ::system/red-subs-icon)
  (assert/is (= "invalid" (browser/text (system/system-detail-textbox "Subscription Status"))))
  (assert/is (some #(= "Red Hat Enterprise Linux Server - Not covered by a valid subscription." %)
                (map browser/text (browser/elements ::system/subs-detail-textbox))))
  (assert/is (= "true" (browser/text (system/system-detail-textbox "Auto-Attach")))))

(defn validate-package-info
  "validate package install/remove info
   Here opr-type is package installed/removed
   msg-format is Package Install/Package Remove"
  [msg-format opr-type {:keys [package package-group]} &[pkg-version]]
  (assert/is (= (join " " [msg-format "scheduled by admin"]) (browser/text ::system/pkg-summary)))
  (if-not (nil? package-group)
    (do
      (assert/is (= (join " " [package-group opr-type]) (browser/text ::system/pkg-request)))
      (assert/is (= (join ["@" package-group]) (browser/text ::system/pkg-parameters)))
      (assert/is (browser/exists? ::system/pkg-result)))
    (do
      (assert/is (= (join " " [opr-type package]) (browser/text ::system/pkg-request)))
      (assert/is (= package (browser/text ::system/pkg-parameters)))
      (assert/is (browser/exists? ::system/pkg-result)))))

(defn configure-product-for-pkg-install
  "Creates a product with fake content repo, returns the product."
  [repo-url]
  (with-unique [provider (katello/newProvider {:name "custom_provider" :org *session-org*})
                product (katello/newProduct {:name "fake" :provider provider})
                testkey (katello/newGPGKey {:name "mykey" :org *session-org*
                                            :contents (slurp
                                                       "http://inecas.fedorapeople.org/fakerepos/zoo/RPM-GPG-KEY-dummy-packages-generator")})
                repo (katello/newRepository {:name "zoo_repo"
                                             :product product
                                             :url repo-url
                                             :repo-type "yum"
                                             :gpg-key testkey})]
    (ui/create-all (list testkey product repo))
    (sync/perform-sync (list repo))
    product))

(defn ui-count-systems "Gets the total count of systems in the given org"
  [org]
  (nav/go-to ::system/page org)
  (Integer/parseInt (fnext (split (browser/text ::system/sys-count) #" "))))

(defn filter-errata "Filter errata based on selected filter term"
  [system {:keys [filter-term]}]
  (nav/go-to ::system/errata-page system)
  (browser/input-text ::system/filter-errata filter-term)
  (assert/is (wd/text-present? filter-term)))

(defn save-cancel-details [save-locator cancel-locator element input-locator requested-value save?]
  (let [orig-text (browser/text element)]
    (browser/click element)
    (browser/clear input-locator)
    (browser/input-text input-locator requested-value)
    (if save?
      (do (browser/click save-locator)
        (let [new-text (browser/text element)]
          (when (not= new-text requested-value)
            (throw+ {:type ::save-failed
                     :requested-value requested-value
                     :new-value new-text
                     :msg "Input field didn't update properly after saving."}))))
      (do (browser/click cancel-locator)
        (let [new-text (browser/text element)]
          (when (not= new-text orig-text)
            (throw+ {:type ::cancel-failed
                     :requested-value requested-value
                     :new-value new-text
                     :msg "Value changed even after clicking cancel button."})))))))

(def save-cancel-sys-details
  (partial #'save-cancel-details
           ::system/save-button ::system/cancel-button))         

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
    :blockers (bz-bugs "1019764" "1019690")
    :data-driven true
    
    (fn [element input-loc new-value save? expected-res]
      (with-unique-system s
        (rest/create s)
        (expecting-error expected-res
                         (nav/go-to ::system/details-page s)
                         (save-cancel-sys-details element input-loc new-value save?))))
	
    [[::system/edit-name ::system/input-name-text "yoursys" false success]
     [::system/edit-name ::system/input-name-text "test.pnq.redhat.com" true success]
     [::system/edit-name ::system/input-name-text (random-ascii-string 256) true (common/errtype ::notification/name-too-long)]
     [::system/edit-name ::system/input-name-text (random-ascii-string 255) true success]
     [::system/edit-description ::system/input-description-text "cancel description" false success]
     [::system/edit-description ::system/input-description-text "System Registration Info" true success]
     [::system/edit-description ::system/input-description-text (random-ascii-string 256) true (common/errtype ::notification/sys-description-255-char-limit)]
     [::system/edit-description ::system/input-description-text (random-ascii-string 255) true success]])
  
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
    (with-unique [org (kt/newOrganization {:name "delsyscount"})]
      (let [env (kt/library org)
            systems (->> {:name "delsys", :env env}
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
        (wd/click ::system/remove)
        (if confirm?
          (do (wd/click ::system/confirmation-yes)
              (assert (rest/not-exists? system)))
          (do (wd/click ::system/confirmation-no)
              (nav/go-to system)))))
    [[false]
     [true]])

  (deftest "Creates org with default custom system key and adds new system"
    :uuid "7d5ff301-b2eb-05a4-aee3-ab60d9583585"
    :blockers (list rest/katello-only)
    (with-unique [org (kt/newOrganization {:name "defaultsysinfo"})
                  system (kt/newSystem {:name "sys"
                                        :sockets "1"
                                        :system-arch "x86_64"
                                        :env (kt/library org)})]
      (ui/create org)
      (org/add-custom-keyname org ::org/system-default-info-page "Manager")
      (rest/create system)
      (nav/go-to system)
      (assert/is (wd/text-present? "Manager"))))

  (deftest "Creates org adds new system then applies custom org default"
    :uuid "0825248e-3c30-5194-28b3-eeff22bb5806"
    (with-unique [org (kt/newOrganization {:name "defaultsysinfo"})
                  system (kt/newSystem {:name "sys"
                                        :sockets "1"
                                        :system-arch "x86_64"})]
      (let [sys1 (assoc system :env (kt/library org))]
        (rest/create-all (list org sys1))
        (nav/go-to sys1)
        (assert/is (not (org/isKeynamePresent? "fizzbuzz")))
        (org/add-custom-keyname org ::org/system-default-info-page "fizzbuzz" {:apply-default true})
        (assert/is (org/isKeynamePresent? "fizzbuzz"))
        (nav/go-to sys1)
        (wd/move-to ::system/existing-custom-items)
        (assert/is (wd/text-present? "fizzbuzz")))))

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

  (deftest "System Details: Add custom info"
    :uuid "577a48a3-6a8e-1324-c8a3-71c959b7f373"
    :blockers (bz-bugs "919373")
    :data-driven true

    (fn [keyname custom-value success?]
      (with-unique-system s
        (rest/create s)
        (ui/update s assoc :custom-info {keyname custom-value})
        (assert/is (= (wd/text-present? keyname) success?))))

    [["Hypervisor" "KVM" true]
     [(random-ascii-string 255) (uniqueify "cust-value") true]
     [(uniqueify "cust-keyname") (random-ascii-string 255) true]
     [(random-unicode-string 10) (uniqueify "cust-value") true]
     [(uniqueify "cust-keyname") (random-unicode-string 10) true]

     (with-meta
       ["foo@!#$%^&*()" "bar_+{}|\"?<blink>hi</blink>" true]
       {:blockers (bz-bugs "951231" "1020425")})
     (with-meta
       ["foo@!#$%^&*()" "bar_+{}|\"?hi" true]
       {:blockers (bz-bugs "1020425")})])
  
  (deftest "System Details: Update custom info"
    :uuid "fd2edd3a-3653-9544-c26b-1c9b4b9ef9d7"
    :blockers (bz-bugs "919373" "951231" "951197" "970079")
    :data-driven true

    (fn [keyname custom-value new-value success?]
      (with-unique-system s
        (rest/create s)
        (let [s (ui/update s assoc :custom-info {keyname custom-value})]
          (assert/is (wd/text-present? custom-value))
          (ui/update s assoc :custom-info {keyname new-value})
          (assert/is (= (wd/text-present? new-value) success?)))))

    [["Hypervisor" "KVM" "Xen" true]
     ["Hypervisor" "KVM" (random-ascii-string 255) true]
     ["Hypervisor" "KVM" (random-unicode-string 10) true]
     ["Hypervisor" "KVM" "bar_+{}|\"?<blink>hi</blink>" true]])
   
  (deftest "System Details: Delete custom info"
    :uuid "b3b7de8e-cf55-1b24-346b-bab3bc209660"
    :blockers (bz-bugs "919373")
    (with-unique-system s
      (rest/create s)
      (let [s (ui/update s assoc :custom-info {"Hypervisor" "KVM"})]
        (assert/is (wd/text-present? "Hypervisor"))
        (ui/update s update-in [:custom-info] dissoc "Hypervisor"))))
  
  (deftest "System Details: Key value limit validation"
    :uuid "fd2edd3a-3653-9544-c26b-1c9b4b9ef9d7"
    :blockers (bz-bugs "919373" "951231" "951197" "970079" "1016203")
    :data-driven true

    (fn [keyname custom-value new-value]
      (with-unique-system s
        (rest/create s)
        (let [s (ui/update s assoc :custom-info {keyname custom-value})]
          (assert/is (wd/text-present? custom-value))
          (expecting-error (common/errtype ::notification/sys-key-value-255-char-limit)
                           (ui/update s assoc :custom-info {keyname new-value})))))

    [["Hypervisor" "KVM" (random-ascii-string 256)]
     ["Hypervisor" "KVM" (random-unicode-string 256)]])

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
    :blockers (bz-bugs "959211" "1015425")
    (let [katello-details {:username (:name *session-user*)
                           :password (:password *session-user*)
                           :org (:name *session-org*)
                           :env (:name test-environment)
                           :force true}]
      (provision/with-queued-client
        ssh-conn
        (client/register ssh-conn (if (rest/is-katello?)
                                    katello-details
                                    (dissoc katello-details :env)))
        (let [hostname (client/my-hostname ssh-conn)
              sys-date (client/get-client-date ssh-conn)
              system (kt/newSystem {:name hostname
                                    :env test-environment})
              details (system/get-details system)]
          (assert/is (= (client/get-distro ssh-conn)
                        (details "OS")))
          (assert/is (= (date sys-date) (first (split (details "Checkin") #" "))))
          (assert/is (= (date sys-date) (first (split (details "Registered") #" "))))
          (assert/is (every? not-empty (vals details)))
          (assert/is (= (client/get-ip-address ssh-conn)
                        (details "ipv4 address")))))))

  (deftest "Review Facts of registered system"
    :uuid "191d75c4-860f-62a4-908b-659ad8acdc4f"
    ;;:blockers no-clients-defined
    :blockers (bz-bugs "959211" "970570")
    (let [katello-details {:username (:name *session-user*)
                           :password (:password *session-user*)
                           :org (:name *session-org*)
                           :env (:name test-environment)
                           :force true}
          headpin-details {:username (:name *session-user*)
                           :password (:password *session-user*)
                           :org (:name *session-org*)
                           :force true}]
      (provision/with-queued-client
        ssh-conn
        (client/register ssh-conn (if (rest/is-katello?)
                                    katello-details
                                    headpin-details))
        (let [hostname (client/my-hostname ssh-conn)
              system (kt/newSystem {:name hostname
                                    :env test-environment})
              facts (system/get-facts system)]
          (assert/is (every? (complement empty?) (vals facts)))))))


  (deftest "System-Details: Validate Activation-key link"
    :uuid "0f8a619c-f2f1-44f4-4ad3-84379abbfa8c"
    :blockers (bz-bugs "959211" "1015249")

    (with-unique [ak (kt/newActivationKey {:name "ak-link"
                                           :env test-environment})]
      (ui/create ak)
      (provision/with-queued-client ssh-conn
        (client/register ssh-conn
                         {:org (:name *session-org*)
                          :activationkey (:name ak)
                          :force true})
        (let [system (kt/newSystem {:name (client/my-hostname ssh-conn) :env test-environment})
              aklink (system/activation-key-link (:name ak))]
          (nav/go-to ::system/details-page system)
          (when (browser/exists?  aklink)
            (wd/click aklink))))))
    
  (deftest "Add/Remove system packages"
    :uuid "e6e74dcc-46e5-48c8-9a2d-0ac33de7dd70"
    :data-driven true
    
    (fn [remove-pkg?]
      (let [repo-url "http://inecas.fedorapeople.org/fakerepos/zoo/"
            product (configure-product-for-pkg-install repo-url)
            package "cow"]
        (provision/with-queued-client
          ssh-conn
          (client/register ssh-conn
                           {:username (:name *session-user*)
                            :password (:password *session-user*)
                            :org (-> product :provider :org :name)
                            :env (:name test-environment)
                            :force true})
          (let [mysys (kt/newSystem {:name (client/my-hostname ssh-conn) :env test-environment})]
            (client/subscribe ssh-conn (system/pool-id mysys product))
            (client/run-cmd ssh-conn "rpm --import http://inecas.fedorapeople.org/fakerepos/zoo/RPM-GPG-KEY-dummy-packages-generator")
            (client/run-cmd ssh-conn "yum repolist")
            (system/package-action mysys {:package package :pkg-action "Package Install"})
            (let [cmd (format "rpm -qa | grep %s" package)
                  cmd_result (client/run-cmd ssh-conn cmd)
                  pkg-version (->> cmd_result :out)]
              (assert/is (client/ok? cmd_result))
              ;(validate-package-info "Package Install" "Package installation:" {:package package} pkg-version)
              (when remove-pkg?
                (system/package-action mysys {:package package :pkg-action "Package Remove"})
                (let [cmd (format "rpm -qa | grep %s" package)
                      cmd_result (client/run-cmd ssh-conn cmd)]
                  (assert/is (->> cmd_result :exit (not= 0))))))))))
                  ;(validate-package-info "Package Remove" "package removed" {:package packages} pkg-version))))))))
    [[true]
     [false]])
  
  (deftest "Add/Remove Package groups"
    :uuid "e7387a9e-53bf-40a8-be66-807dcafd0c20"
    :data-driven true
    
    (fn [remove-group?]
      (let [repo-url "http://inecas.fedorapeople.org/fakerepos/zoo/"
            product (configure-product-for-pkg-install repo-url)
            package-groups "birds"]
        (provision/with-queued-client
          ssh-conn
          (client/register ssh-conn
                           {:username (:name *session-user*)
                            :password (:password *session-user*)
                            :org (-> product :provider :org :name)
                            :env (:name test-environment)
                            :force true})
          (let [mysys (kt/newSystem {:name (client/my-hostname ssh-conn) :env test-environment})]
            (client/subscribe ssh-conn (system/pool-id mysys product))
            (client/run-cmd ssh-conn "rpm --import http://inecas.fedorapeople.org/fakerepos/zoo/RPM-GPG-KEY-dummy-packages-generator")
            (client/run-cmd ssh-conn "yum repolist")
            (system/package-action mysys {:package package-groups :pkg-action "Group Install"})
            (let [cmd_result (client/run-cmd ssh-conn "rpm -q cockateel duck penguin stork lion wolf tiger dolphin bear")
                  pkg-version (->> cmd_result :out)]
              (assert/is (client/ok? cmd_result)))
              ;(validate-package-info "Package Group Install" "package group installed" {:package-group package-groups}))
            (when remove-group?
              (system/package-action mysys {:package-group package-groups :pkg-action "Group Remove"})
              (let [cmd_result (client/run-cmd ssh-conn "rpm -q cockateel duck penguin stork")]
                (assert/is (->> cmd_result :exit (not= 0)))))))))
               ; (validate-package-info "Package Group Remove" "package group removed" {:package-group package-groups})))))))

    [[true]
     [false]])

  
  (deftest "Update/Remove selected system package"
    :uuid "aaca29c2-fdff-4901-81b6-98db22871edd"
    :data-driven true
    (fn [remove-pkg?]
      (let [repo-url "http://inecas.fedorapeople.org/fakerepos/zoo/"
            product (configure-product-for-pkg-install repo-url)
            package-name "walrus-0.71-1.noarch"
            package (first (split package-name #"-+"))]
        (provision/with-queued-client
          ssh-conn
          (client/run-cmd ssh-conn "wget -O /etc/yum.repos.d/zoo.repo https://gist.github.com/sghai/6387115/raw/")
          (let [cmd (format "yum install -y %s" package-name)]
            (client/run-cmd ssh-conn cmd))
          (client/register ssh-conn
                           {:username (:name *session-user*)
                            :password (:password *session-user*)
                            :org (-> product :provider :org :name)
                            :env (:name test-environment)
                            :force true})
          (client/run-cmd ssh-conn "rm -f /etc/yum.repos.d/zoo.repo")
          (let [mysys (-> {:name (client/my-hostname ssh-conn) :env test-environment}
                        katello/newSystem)]
            (client/subscribe ssh-conn (system/pool-id mysys product))
            (client/run-cmd ssh-conn "rpm --import http://inecas.fedorapeople.org/fakerepos/zoo/RPM-GPG-KEY-dummy-packages-generator")
            (client/run-cmd ssh-conn "yum repolist")
            (system/package-action mysys {:package package :pkg-action "Package Update"})
            (let [cmd_result (client/run-cmd ssh-conn "rpm -qa | grep walrus-5.21-1.noarch")
                  pkg-version (->> cmd_result :out)]
              (assert/is (client/ok? cmd_result))
            ;  (validate-package-info "Package Update" "package updated" {:package package} pkg-version)
              (when remove-pkg?
                (system/remove-selected-package mysys {:package package})
                (let [cmd_result (client/run-cmd ssh-conn "rpm -qa | grep walrus-5.21-1.noarch")]
                  (assert/is (->> cmd_result :exit (not= 0))))))))))
                ;(validate-package-info "Package Remove" "package removed" {:package package} pkg-version)))))))
    
    [[true]
     [false]])
  
  (deftest "Search a Package from package-list"
    :uuid "5dc869d7-2604-4524-85f1-574722e9dd59"
    (let [package-name "walrus-0.71-1.noarch"]
      (provision/with-queued-client
        ssh-conn
        (client/run-cmd ssh-conn "wget -O /etc/yum.repos.d/zoo.repo https://gist.github.com/sghai/6387115/raw/")
        (let [cmd (format "yum install -y %s" package-name)]
          (client/run-cmd ssh-conn cmd))
        (client/register ssh-conn
                         {:username (:name *session-user*)
                          :password (:password *session-user*)
                          :org (:name *session-org*)
                          :env (:name test-environment)
                          :force true})
        (let [mysys (-> {:name (client/my-hostname ssh-conn) :env test-environment}
                      katello/newSystem)]
          (system/filter-package mysys {:package package-name})
          (assert/is (= package-name (browser/text (system/get-filtered-package package-name))))))))
  
  (deftest "Filter Errata"
    :uuid "ed64eea5-4c37-4810-8f43-8da0bfbced43"
    (let [repo-url "http://hhovsepy.fedorapeople.org/fakerepos/zoo4/"
          product (configure-product-for-pkg-install repo-url)]
      (provision/with-queued-client
        ssh-conn
        (client/run-cmd ssh-conn "wget -O /etc/yum.repos.d/zoo.repo https://gist.github.com/sghai/6387115/raw/")
        (client/run-cmd ssh-conn "yum install -y cow cheetah pig zebra")
        (client/register ssh-conn
                         {:username (:name *session-user*)
                          :password (:password *session-user*)
                          :org (-> product :provider :org :name)
                          :env (:name test-environment)
                          :force true})
        (client/run-cmd ssh-conn "rm -f /etc/yum.repos.d/zoo.repo")
        (let [mysys (-> {:name (client/my-hostname ssh-conn) :env test-environment}
                      katello/newSystem)]
          (client/subscribe ssh-conn (system/pool-id mysys product))
          (client/run-cmd ssh-conn "rpm --import http://inecas.fedorapeople.org/fakerepos/zoo/RPM-GPG-KEY-dummy-packages-generator")
          (client/run-cmd ssh-conn "yum repolist")
          (client/sm-cmd ssh-conn :refresh)
          (doall (for [errata [{:filter-term "bugfix"}
                               {:filter-term "RHEA-2012:783"}
                               {:filter-term "zebra_Erratum"}
                               {:filter-term "cow_Erratum"}]]
                   (filter-errata mysys errata)))))))
  
  (deftest "Re-registering a system to different environment"
    :uuid "72dfb70e-51c5-b074-4beb-7def65550535"
    :blockers (conj (bz-bugs "959211") rest/katello-only)
    
    (let [org (kt/newOrganization {:name (uniqueify "sys-org")})
          repo (fresh-repo org
                           "http://inecas.fedorapeople.org/fakerepos/cds/content/safari/1.0/x86_64/rpms/")
          env-dev (katello/newEnvironment {:name (uniqueify "env-dev")
                                           :org org})
          env-test (katello/newEnvironment {:name (uniqueify "env-test")
                                            :org org})
          cv (promote-published-content-view org env-dev repo)
          cs (kt/newChangeset {:name "cs"
                               :env env-test
                               :content (list cv)})]
      (ui/create env-test)
      (changeset/promote-delete-content cs)
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
            (nav/go-to ::system/details-page mysys)
            (wd/selected? (system/check-selected-env (:name env))))
          (assert/is (not= (:environment_id mysys)
                           (rest/get-id env-dev)))))))
  
  (deftest "Register a system and validate subscription tab"
    :uuid "7169755a-379a-9e24-37eb-cf222e6beb86"
    :blockers (conj (bz-bugs "1015256" "1016625") rest/katello-only)
    (with-unique [repo (fresh-repo *session-org* "http://inecas.fedorapeople.org/fakerepos/zoo/")]
      (create-recursive repo)
      (sync/perform-sync (list repo))
      (provision/with-queued-client
        ssh-conn
        (client/register ssh-conn
                         {:username (:name *session-user*)
                          :password (:password *session-user*)
                          :org (:name *session-org*)
                          :env (:name test-environment)
                          :force true})
        (let [hostname (client/my-hostname ssh-conn)
              system (kt/newSystem {:name hostname :env test-environment})]
          (validate-sys-subscription system)))))

  (deftest "Register a system using multiple activation keys"
    :uuid "a39bf0f7-7e7b-1e54-cdf3-d1442d6e6a6a"
    :blockers (conj (bz-bugs "1015249") rest/katello-only)
    (with-unique [[ak1 ak2] (kt/newActivationKey {:name "ak1"
                                                  :env test-environment
                                                  :description "auto activation key"})]
      (ui/create-all (list ak1 ak2))
      (let [ak1-name (:name ak1)
            ak2-name (:name ak2)
            ak-name (join "," [ak1-name ak2-name])]
        (provision/with-queued-client ssh-conn
          (client/register ssh-conn
                           {:org (:name *session-org*)
                            :activationkey ak-name
                            :force true})
          (let [system (kt/newSystem {:name (client/my-hostname ssh-conn) :env test-environment})]
            (doseq [ak [ak1 ak2]]
              (let [aklink (system/activation-key-link (:name ak))]
                (nav/go-to ::system/details-page system)
                (when (browser/exists?  aklink)
                  (wd/click aklink)))))))))

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
            (assert/is (client/ok? result)))))))

  (deftest "Install package after moving a system from one env to other"
    :uuid "960cc577-e045-f9d4-7383-dec4e5eed00b"
    :blockers (conj (bz-bugs "959211" "970570")
                    rest/katello-only
                    (auto-issue "791"))

    (let [org (kt/newOrganization {:name (uniqueify "sys-org")})
          repo (fresh-repo org
                           "http://inecas.fedorapeople.org/fakerepos/cds/content/safari/1.0/x86_64/rpms/")
          product (-> repo kt/product)
          env-dev (katello/newEnvironment {:name (uniqueify "env-dev")
                                           :org org})
          env-test (katello/newEnvironment {:name (uniqueify "env-test")
                                            :org org})
          cv (promote-published-content-view org env-dev repo)
          cs (kt/newChangeset {:name (uniqueify "cs")
                               :env env-test
                               :content (list cv)})]
      (ui/create env-test)
      (changeset/promote-delete-content cs)
      (provision/with-queued-client
        ssh-conn
        (client/register ssh-conn
                         {:username (:name *session-user*)
                          :password (:password *session-user*)
                          :org (-> env-dev :org :name)
                          :env (:name env-dev)
                          :force true})
        (let [mysys (kt/newSystem {:name (client/my-hostname ssh-conn) :env env-dev})]
          (assert/is (= (:name env-dev) (system/environment mysys)))
          (ui/update mysys assoc :env env-test :cv cv)
          (assert/is (= (:name env-test) (system/environment mysys)))
          (client/subscribe ssh-conn (system/pool-id mysys product))
          (client/run-cmd ssh-conn "rpm --import http://inecas.fedorapeople.org/fakerepos/zoo/RPM-GPG-KEY-dummy-packages-generator")
          (client/sm-cmd ssh-conn :refresh)
          (client/run-cmd ssh-conn "yum repolist")
          (system/package-action mysys {:package "cow" :pkg-action "Package Install"})
          (let [cmd_result (client/run-cmd ssh-conn "rpm -q cow")]
            (assert/is (client/ok? cmd_result)))))))

  (deftest "Upload redhat manifest and enable rhel repos to validate release-version"
    :uuid "5aa3f4a0-7614-4525-93fe-27a083056ff3"
    (let [org (upload-manifest-with-rhel-subscriptions)]
      (provision/with-queued-client
        ssh-conn
        (client/register ssh-conn {:username (:name *session-user*)
                                   :password (:password *session-user*)
                                   :org (:name org)
                                   :env (:name (kt/library org))
                                   :force true})
        (let [mysys (-> {:name (client/my-hostname ssh-conn) :env (kt/library org)}
                      katello/newSystem)]
          (assert/is (= "6.1\n6.2\n6.3\n6.4" (system/get-release-version mysys)))))))
  
  (deftest "Upload a manifest with rhel subscriptions and autosubscribe a client with it"
    :uuid "320aa021-338d-490b-b4b3-575d0ddfa59f"
    (let [org (upload-manifest-with-rhel-subscriptions)]
      (provision/with-queued-client
        ssh-conn
        (client/register ssh-conn {:username (:name *session-user*)
                                   :password (:password *session-user*)
                                   :org (:name org)
                                   :env (:name (kt/library org))
                                   :force true
                                   :autosubscribe true})
        (let [mysys (-> {:name (client/my-hostname ssh-conn) :env (kt/library org)}
                      katello/newSystem)]
          (nav/go-to ::system/details-page mysys)
          (browser/click ::system/subscriptions)
          (browser/exists? :system/green-subs-icon)
          (assert/is (= "valid" (browser/text (system/system-detail-textbox "Subscription Status"))))
          (assert/is (= "true" (browser/text (system/system-detail-textbox "Auto-Attach"))))
          (assert/is (= "Red Hat Employee Subscription" (browser/text ::system/current-subscription-name)))))))
  
  (deftest "Update all system packages"
    :uuid "6a0df4cd-195d-46fa-8be0-984dfc4ec978"
    (let [repo-url "http://hhovsepy.fedorapeople.org/fakerepos/zoo4/"
          product (configure-product-for-pkg-install repo-url)]
      (provision/with-queued-client
        ssh-conn
        (client/run-cmd ssh-conn "wget -O /etc/yum.repos.d/zoo.repo https://gist.github.com/sghai/7479364/raw/")
        (let [cmd "yum install -y acme-package-1.0.1-1.noarch bat-3.10.6-1.noarch bird-5.1.9-1.noarch cheetah-5.1.8-1.noarch cow-5.3.0-1.noarch eagle-9.8.9-1.noarch fish-4.10.0-1.noarch fox-10.8.0-1.noarch"]
          (client/run-cmd ssh-conn cmd))
        (client/register ssh-conn
                         {:username (:name *session-user*)
                          :password (:password *session-user*)
                          :org (-> product :provider :org :name)
                          :env (:name test-environment)
                          :force true})
        (client/run-cmd ssh-conn "rm -f /etc/yum.repos.d/zoo.repo")
        (let [mysys (-> {:name (client/my-hostname ssh-conn) :env test-environment}
                      katello/newSystem)]
          (client/subscribe ssh-conn (system/pool-id mysys product))
          (client/run-cmd ssh-conn "rpm --import http://inecas.fedorapeople.org/fakerepos/zoo/RPM-GPG-KEY-dummy-packages-generator")
          (client/run-cmd ssh-conn "yum repolist")
          (system/update-all mysys)
          (let [cmd_result (client/run-cmd ssh-conn "rpm -q acme-package-1.1.2-1.noarch bat-3.10.7-1.noarch bird-5.1.11-1.noarch cheetah-5.1.10-1.noarch cow-5.3.2-1.noarch eagle-9.8.10-1.noarch fish-4.10.3-1.noarch fox-10.8.2-1.noarch")]
            (assert/is (client/ok? cmd_result)))))))
 
(deftest "Systems cannot retrieve content from environment
	 after a remove changeset has been applied"
        :uuid "7b2d6b28-a0bc-4c82-bbad-d7e200ad8ff5"
        :blockers (conj (bz-bugs "994946") rest/katello-only)
        (let [org (uniqueify (kt/newOrganization {:name "redhat-org"}))
              envz (take 3 (uniques (kt/newEnvironment {:name "env", :org org})))
              repos (rh-repos/describe-repos-to-enable-disable fake/enable-nature-repos)
              products (->> (map :reposet repos) (map :product) distinct)
              target-env (first envz)
              cv (-> {:name "content-view" :org org :published-name "publish-name"}
                             kt/newContentViewDefinition uniqueify)
              cs (-> {:name "cs" :env target-env :content (list cv)}
                             kt/newChangeset uniqueify)]
          (manifest/setup-org envz repos)
          (sync/verify-all-repos-synced repos)
          (ui/create cv)
          (ui/update cv assoc :products products)
          (views/publish {:content-defn cv
                          :published-name (:published-name cv)
                          :description "test pub"
                          :org org})
          (changeset/promote-delete-content cs)
          (provision/with-queued-client
            ssh-conn
             (client/register ssh-conn {:username (:name *session-user*)
                                        :password (:password *session-user*)
                                        :org (:name org)
                                        :env (:name target-env)
                                        :force true})
             (let [mysys (-> {:name (client/my-hostname ssh-conn) :env target-env}
                             katello/newSystem)
                   deletion-changeset (-> {:name "deletion-cs" :content (list cv)
                                           :env target-env :deletion? true}
                                          katello/newChangeset
                                          uniqueify)]
               (doseq [prd1 products]
                 (client/subscribe ssh-conn (system/pool-id mysys prd1)))
               (client/sm-cmd ssh-conn :refresh)
               (client/run-cmd ssh-conn "yum repolist")
               (let [result (client/run-cmd ssh-conn "yum install cow -y --nogpgcheck")]
                 (assert/is (->> result :exit (= 0))))
               (changeset/promote-delete-content deletion-changeset)
               (client/sm-cmd ssh-conn :refresh)
               (client/run-cmd ssh-conn "yum repolist")
               (let [result (client/run-cmd ssh-conn "yum install cow -y --nogpgcheck")]
                 (assert/is (->> result :exit (= 1)))))))))
