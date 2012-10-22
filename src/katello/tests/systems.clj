(ns katello.tests.systems
  (:refer-clojure :exclude [fn])
  (:require (katello [api-tasks :as api]
                     [validation :as val]
                     [organizations :as org]
                     [client :as client]
                     [tasks :refer :all] 
                     [ui-tasks :refer :all] 
                     [systems :as system] 
                     [conf :refer [*session-user* *session-password* config *environments*]]) 
            [katello.client.provision :as provision]
            (test.tree [script :refer :all] 
                       [builder :refer [union]])
            
            [serializable.fn :refer [fn]]
            [tools.verify :refer [verify-that]]
            [bugzilla.checker :refer [open-bz-bugs]]
            [slingshot.slingshot :refer [throw+]]
            [deltacloud :as cloud]))


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
    (navigate :named-systems-page {:system-name new-name})))

(defn verify-system-appears-on-env-page
   [system]
  (navigate :named-system-environment-page
            {:env-name test-environment
             :system-name (:name system)})
  (verify-that (= (:environment_id system)
                  (api/get-id-by-name :environment test-environment))))

(defn step-create-system-group
  [{:keys [group-name]}]
  (system/create-group group-name {:description "rh system group"}))

(defn step-edit-system-group
  [{:keys [group-name group-new-limit group-new-description]}]
  (system/edit-group group-name {:new-limit group-new-limit
                                 :description group-new-description}))

(defn step-add-new-system-to-new-group
  "Creates a system and system group, adds the system to the system group."
  [{:keys [group-name system-name] :as m}]
  (do (system/create system-name {:sockets "1"
                              :system-arch "x86_64"})
      (step-create-system-group m)
      (system/add-to-group group-name system-name)))

(defn mkstep-remove-system-group
  "Creates a fn to remove a system group given a request map. Optional
   arg which-group determines which key contains the group to remove.
   Defaults to :system-group."
  [which-group]
  (fn [{:keys [group-name also-remove-systems?] :as req}]
    (system/remove-group (req which-group)
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
      (verify-that (not (some #{system-name} all-system-names)))
      (verify-that (some #{system-name} all-system-names)))))

(defn step-copy-system-group
  "Copies a system group with a hardcoded description."
  [{:keys [group-name copy-name]}]
  (system/copy-group group-name copy-name {:description "copied system group"}))

(defn step-remove-sys-from-copied-system-group
  "Remove the system from copied system group."
  [{:keys [copy-name system-name]}]
  (system/remove-from-group copy-name system-name))

;; Tests

(defgroup system-group-tests
  :blockers api/katello-only
  :group-setup #(api/ensure-env-exist "dev" {:prior "Library"})
  :test-setup (fn [& _] (org/switch))
  
  (deftest "Create a system group"
    (with-unique [group-name "fed"]
      (system/create-group group-name {:description "rh system-group"}))
    
    (deftest "Copying with similar sg-name not allowed"
      (with-unique [group-name "fed1"]
        (system/create-group group-name {:description "rh system-group"})
        (expecting-error (errtype :katello.notifications/sg-name-taken-error)
          (system/copy-group group-name group-name {:description "copied system group"}))))

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
        (system/create-group group-name)
        (system/edit-group group-name {:new-limit 4}))

      
      (deftest "Edit system limit of a system group, then set back to unlimited"
        (with-unique [group-name "sg"]
          (system/create-group group-name)
          (system/edit-group group-name {:new-limit 4})
          (system/edit-group group-name {:new-limit :unlimited}))
        

        (deftest "System group system limit validation"
          :data-driven true

          (fn [limit pred]
            (with-unique [group-name "sg-val"]
              (system/create-group group-name)
              (expecting-error pred (system/edit-group
                                     group-name {:new-limit limit}))))
          
          [(with-meta
             ["-1"   (errtype :katello.notifications/max-systems-must-be-positive)]
             {:blockers (open-bz-bugs "848564")})
           ["-100" (errtype :katello.notifications/max-systems-must-be-positive)]
           [""     (errtype :katello.notifications/max-systems-must-be-positive)]
           ["0"    (errtype :katello.notifications/max-systems-may-not-be-zero)]])))
    
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
            (system/create-group group-name)
            (let [syscount  (system/get-group-system-count group-name)]
              (system/create system-name {:sockets "1"
                                :system-arch "x86_64"})
              (system/add-to-group group-name system-name)
              (verify-that (= (inc syscount) (system/get-group-system-count group-name)))))))
       
      (deftest "Remove a system from a system group and check count is -1"
        :blockers (open-bz-bugs "857031")
        (with-unique [system-name "mysystem"
                      group-name "my-group"]
          (do
            (system/create-group group-name)
            (system/create system-name {:sockets "1"
                           :system-arch "x86_64"})
            (system/add-to-group group-name system-name)
            (let [syscount  (system/get-group-system-count group-name)]
              (system/remove-from-group group-name system-name)
              (verify-that (= (dec syscount) (system/get-group-system-count group-name)))))))
      
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
  :test-setup (fn [& _] (org/switch))
  
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

    (create-activation-key {:name (uniqueify "auto-key")
                            :description "my description"
                            :environment test-environment})

    (deftest "Create an activation key with i18n characters"
      :data-driven true
      (fn [name]
        (with-unique [ak-name name]
          (create-activation-key {:name ak-name
                                  :description "my description"
                                  :environment test-environment} )))
      val/i8n-chars)
    
    (deftest "Remove an activation key"
      (with-unique [ak-name "auto-key-deleteme"]
        (create-activation-key {:name ak-name
                                :description "my description"
                                :environment test-environment} )
        (delete-activation-key ak-name)))

    
    (deftest "activation-key-dupe-disallowed"
      (with-unique [ak-name "auto-key"]
        (val/expecting-error-2nd-try val/duplicate-disallowed
          (create-activation-key
           {:name ak-name
            :description "my description"
            :environment test-environment})))))
  
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
        (verify-that (= (client/get-distro ssh-conn)
                        (system/get-os (client/my-hostname ssh-conn))))))
    
  system-group-tests)

