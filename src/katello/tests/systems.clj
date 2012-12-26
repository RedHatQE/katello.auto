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
                     [activation-keys :as ak]
                     [systems :as system]
                     [system-groups :as group]
                     [fake-content  :as fake]
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

(defn step-create-system-group
  [{:keys [group-name]}]
  (group/create group-name {:description "rh system group"}))

(defn step-edit-system-group
  [{:keys [group-name group-new-limit group-new-description]}]
  (group/edit group-name {:new-limit group-new-limit
                          :description group-new-description}))

(defn step-add-new-system-to-new-group
  "Creates a system and system group, adds the system to the system group."
  [{:keys [group-name system-name] :as m}]
  (do (system/create system-name {:sockets "1"
                                  :system-arch "x86_64"})
      (step-create-system-group m)
      (group/add-to group-name system-name)))

(defn mkstep-remove-system-group
  "Creates a fn to remove a system group given a request map. Optional
   arg which-group determines which key contains the group to remove.
   Defaults to :system-group."
  [which-group]
  (fn [{:keys [group-name also-remove-systems?] :as req}]
    (group/remove (req which-group)
                  {:also-remove-systems? also-remove-systems?})))

(def step-remove-system-group (mkstep-remove-system-group :group-name))
(def step-remove-system-group-copy (mkstep-remove-system-group :copy-name))

(defn step-verify-system-presence
  "Verifies that the system is either present, or not present after
   removing its system group. Depends on whether :also-remove-systems?
   is true in the input map (if true, then verifies system is *not*
   present."
  [{:keys [system-name also-remove-systems?]}]
  (let [all-system-names (map :name (api/all-entities :system))]
    (if also-remove-systems?
      (assert/is (not (some #{system-name} all-system-names)))
      (assert/is (some #{system-name} all-system-names)))))

(defn step-copy-system-group
  "Copies a system group with a hardcoded description."
  [{:keys [group-name copy-name]}]
  (group/copy group-name copy-name {:description "copied system group"}))

(defn step-remove-sys-from-copied-system-group
  "Remove the system from copied system group."
  [{:keys [copy-name system-name]}]
  (group/remove-from copy-name system-name))

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

(defgroup system-group-tests
  :blockers api/katello-only
  :group-setup #(api/ensure-env-exist "dev" {:prior "Library"})
  :test-setup org/before-test-switch
  
  (deftest "Create a system group"
    (with-unique [group-name "fed"]
      (group/create group-name {:description "rh system-group"}))
    
    (deftest "Copying with similar sg-name not allowed"
      (with-unique [group-name "fed1"]
        (group/create group-name {:description "rh system-group"})
        (expecting-error (common/errtype :katello.notifications/sg-name-taken-error)
                         (group/copy group-name group-name {:description "copied system group"}))))

    (deftest "Edit a system group"
      :data-driven true
      
      (fn [data]
        (do-steps (merge data
                         (uniqueify-vals {:group-name "sg"}))
                  step-create-system-group
                  step-edit-system-group))

      [[{:group-new-limit 4}]
       [{:group-new-limit 8
         :group-new-description "updated description"}]
       [{:group-new-description "updated description"}]])
    

    (deftest "Edit system limit of a system group"
      (with-unique [group-name "sg"]
        (group/create group-name)
        (group/edit group-name {:new-limit 4}))

      
      (deftest "Edit system limit of a system group, then set back to unlimited"
        (with-unique [group-name "sg"]
          (group/create group-name)
          (group/edit group-name {:new-limit 4})
          (group/edit group-name {:new-limit :unlimited}))
        

        (deftest "System group system limit validation"
          :data-driven true

          (fn [limit pred]
            (with-unique [group-name "sg-val"]
              (group/create group-name)
              (expecting-error pred (group/edit
                                     group-name {:new-limit limit}))))
          
          [(with-meta
             ["-1"   (common/errtype :katello.notifications/max-systems-must-be-positive)]
             {:blockers (open-bz-bugs "848564")})
           ["-100" (common/errtype :katello.notifications/max-systems-must-be-positive)]
           [""     (common/errtype :katello.notifications/max-systems-must-be-positive)]
           ["0"    (common/errtype :katello.notifications/max-systems-may-not-be-zero)]])))
    
    (deftest "Add a system to a system group"
      :blockers (open-bz-bugs "845668")
      (do-steps (uniqueify-vals
                 {:system-name "mysystem"
                  :group-name "my-group"})
                step-add-new-system-to-new-group)
      
      (deftest "Add a system to a system group and check count is +1"
        (with-unique [system-name "mysystem"
                      group-name "my-group"]
          (do
            (group/create group-name)
            (let [syscount  (group/system-count group-name)]
              (system/create system-name {:sockets "1"
                                          :system-arch "x86_64"})
              (group/add-to group-name system-name)
              (assert/is (= (inc syscount) (group/system-count group-name)))))))
      
      (deftest "Remove a system from a system group and check count is -1"
        :blockers (open-bz-bugs "857031")
        (with-unique [system-name "mysystem"
                      group-name "my-group"]
          (do
            (group/create group-name)
            (system/create system-name {:sockets "1"
                                        :system-arch "x86_64"})
            (group/add-to group-name system-name)
            (let [syscount  (group/system-count group-name)]
              (group/remove-from group-name system-name)
              (assert/is (= (dec syscount) (group/system-count group-name)))))))
      
      (deftest "Unregister a system & check count under sys-group details is -1"
        (with-unique [system-name "mysystem"
                      group-name "my-group"]
          (let [target-env (first *environments*)]
            (api/ensure-env-exist target-env {:prior library})
            (do
              (group/create group-name)
              (system/create system-name {:sockets "1"
                                          :system-arch "x86_64"})
              (group/add-to group-name system-name)
              (provision/with-client "check-sys-count"
                 ssh-conn
                 (client/register ssh-conn
                                  {:username *session-user*
                                   :password *session-password*
                                   :org "ACME_Corporation"
                                   :env target-env
                                   :force true})
                 (let [mysys (client/my-hostname ssh-conn)]
                   (group/add-to group-name mysys)
                   (let [syscount (group/system-count group-name)]
                     (client/run-cmd ssh-conn "subscription-manager unregister")
                     (assert/is (= (dec syscount) (group/system-count group-name))))))))))
      
      (deftest "Delete a system group"
        :data-driven true

        (fn [data]
          (do-steps (merge data
                           (uniqueify-vals {:system-name "mysystem"
                                            :group-name "to-del"}))
                    step-add-new-system-to-new-group
                    step-remove-system-group
                    step-verify-system-presence))
        
        [[{:also-remove-systems? true}]
         [{:also-remove-systems? false}]])
      
      (deftest "Remove a system from copied system group"
        :blockers (open-bz-bugs "857031")
        (do-steps (uniqueify-vals
                   {:system-name  "mysys"
                    :group-name  "copygrp"
                    :copy-name  "copy_mysys"})
                  step-add-new-system-to-new-group
                  step-copy-system-group
                  step-remove-sys-from-copied-system-group))

      (deftest "Copy a system group"
        (do-steps (uniqueify-vals
                   {:system-name  "mysystem"
                    :group-name  "copyme"
                    :copy-name  "imthecopy"})
                  step-add-new-system-to-new-group
                  step-copy-system-group)
        

        (deftest "Delete a copied system group"
          :data-driven true

          (fn [data]
            (do-steps (merge data (uniqueify-vals
                                   {:system-name  "mysystem"
                                    :group-name  "to-del"
                                    :copy-name  "imthecopy"}))
                      step-add-new-system-to-new-group 
                      step-copy-system-group
                      step-remove-system-group-copy
                      step-verify-system-presence))
          
          [[{:also-remove-systems? true}]
           [{:also-remove-systems? false}]])))))



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
  
  (deftest "Create an activation key" 
    :blockers (open-bz-bugs "750354")

    (ak/create {:name (uniqueify "auto-key")
                :description "my description"
                :environment test-environment})

    (deftest "Create an activation key with i18n characters"
      :data-driven true
      (fn [name]
        (with-unique [ak-name name]
          (ak/create {:name ak-name
                      :description "my description"
                      :environment test-environment} )))
      val/i8n-chars)
    
    (deftest "Remove an activation key"
      (with-unique [ak-name "auto-key-deleteme"]
        (ak/create {:name ak-name
                    :description "my description"
                    :environment test-environment} )
        (ak/delete ak-name)))

    
    (deftest "activation-key-dupe-disallowed"
      (with-unique [ak-name "auto-key"]
        (val/expecting-error-2nd-try val/duplicate-disallowed
                                     (ak/create
                                      {:name ak-name
                                       :description "my description"
                                       :environment test-environment}))))
    
    (deftest "create activation keys with subscriptions"
      (with-unique [ak-name "act-key"
                    test-org1 "redhat-org"]
        (do
          (let [envz (take 3 (unique-names "env"))]
            (fake/setup-org test-org1 envz)
            (org/switch test-org1)
            (ak/create {:name ak-name
                        :description "my act keys"
                        :environment (first envz)})
            (ak/add-subscriptions ak-name fake/subscription-names)
            (assert/is (some #{(first fake/subscription-names)} 
                             (ak/get-subscriptions ak-name))))))))
  
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
     [{:package-group "birds"}]])
  
  system-group-tests)

