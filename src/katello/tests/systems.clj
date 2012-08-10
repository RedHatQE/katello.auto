(ns katello.tests.systems
  (:refer-clojure :exclude [fn])
  (:use katello.tasks
        katello.ui-tasks
        test.tree.script
        [test.tree.builder :only [union]]
        [serializable.fn :only [fn]]
        [katello.conf :only [config *environments*]]
        [tools.verify :only [verify-that]]
        [bugzilla.checker :only [open-bz-bugs]]
        [slingshot.slingshot :only [throw+]])
  (:require (katello [api-tasks :as api]
                     [validation :as val])))

;; Functions

(defn create-test-environment [] 
  (def test-environment (first *environments*))
  (api/with-admin
    (api/ensure-env-exist test-environment {:prior library})))

(defn register-new-test-system []
  (api/with-admin
    (api/with-env test-environment
      (api/create-system (uniqueify "newsystem")
                         {:facts (api/random-facts)}))))

(defn verify-system-rename [system]
  (with-unique [new-name "yoursys"] 
    (edit-system (:name system) {:new-name new-name})
    (navigate :named-systems-page {:system-name new-name})))

(defn verify-system-appears-on-env-page
   [system]
  (navigate :named-system-environment-page
            {:env-name test-environment
             :system-name (:name system)})
  (verify-that (= (:environment_id system)
                  (api/with-admin
                    (api/get-id-by-name :environment test-environment)))))

(defn step-add-new-system-to-new-group
  "Creates a system and system group, adds the system to the system group."
  [{:keys [group-name system-name]}]
  (do (create-system system-name {:sockets "1"
                                  :system-arch "x86_64"})
      (create-system-group group-name {:description "rh system group"})
      (add-to-system-group group-name system-name)))

(defn mkstep-remove-system-group
  "Creates a fn to remove a system group given a request map. Optional
   arg which-group determines which key contains the group to remove.
   Defaults to :system-group."
  [which-group]
  (fn [{:keys [group-name also-remove-systems?] :as req}]
    (remove-system-group (req which-group)
                         {:also-remove-systems? also-remove-systems?})))

(def step-remove-system-group (mkstep-remove-system-group :group-name))
(def step-remove-system-group-copy (mkstep-remove-system-group :copy-name))

(defn step-verify-system-presence
  "Verifies that the system is either present, or not present after
   removing its system group. Depends on whether :also-remove-systems?
   is true in the input map (if true, then verifies system is *not*
   present."
  [{:keys [system-name also-remove-systems?]}]
  (let [all-system-names (map :name (api/with-admin (api/all-entities :system)))]
    (if also-remove-systems?
      (verify-that (not (some #{system-name} all-system-names)))
      (verify-that (some #{system-name} all-system-names)))))

(defn step-copy-system-group
  "Copies a system group with a hardcoded description."
  [{:keys [group-name copy-name]}]
  (copy-system-group group-name copy-name {:description "copied system group"}))

(defn do-steps
  "Call all fs in order, with single argument m"
  [m & fs]
  ((apply juxt fs) m))

;; Tests

(defgroup system-group-tests
  :group-setup (fn []
                 (api/with-admin
                   (api/ensure-env-exist "dev" {:prior "Library"})))
  
  (deftest "Create a system group"
    (with-unique [group-name "fed"]
      (create-system-group group-name {:description "rh system-group"}))


    (deftest "Add a system to a system group"
      :blockers (open-bz-bugs "845668")
      (do-steps (uniqueify-vals
                 {:system-name "mysystem"
                  :group-name "my-group"})
                step-add-new-system-to-new-group))

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
         [{:also-remove-systems? false}]]))))



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

    (with-unique [provider-name "subscr-prov"
                  product-name "subscribe-me"]
      (api/with-admin
        (api/create-provider provider-name)
        (api/create-product product-name {:provider-name provider-name}))
      (subscribe-system {:system-name (:name (register-new-test-system))
                         :add-products [product-name]})))

  (deftest "Set a system to autosubscribe with no SLA preference"
    :blockers (open-bz-bugs "845261")
    (subscribe-system {:system-name (:name (register-new-test-system))
                       :auto-subscribe true
                       :sla "No Service Level Preference"}))
  
  (deftest "Create an activation key" 
    :blockers (open-bz-bugs "750354")

    (create-activation-key {:name (uniqueify "auto-key")
                            :description "my description"
                            :environment test-environment})

    
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
  system-group-tests)



