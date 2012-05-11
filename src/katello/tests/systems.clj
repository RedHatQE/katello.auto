(ns katello.tests.systems
  (:refer-clojure :exclude [fn])
  (:use katello.tasks
        katello.ui-tasks
        test.tree.script
        [test.tree.builder :only [union]]
        [serializable.fn :only [fn]]
        [katello.conf :only [config *environments*]]
        [com.redhat.qe.verify :only [verify-that]]
        [bugzilla.checker :only [open-bz-bugs]])
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
    (edit-system (:name system) :new-name new-name)
    (navigate :named-systems-page {:system-name new-name})))

(defn verify-system-appears-on-env-page
   [system]
  (navigate :named-system-environment-page
            {:env-name test-environment
             :system-name (:name system)})
  (verify-that (= (:environment_id system)
                  (api/with-admin
                    (api/get-id-by-name :environment test-environment)))))

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

    (with-unique [provider-name "subscr-prov"
                  product-name "subscribe-me"]
      (api/with-admin
        (api/create-provider provider-name)
        (api/create-product product-name {:provider-name provider-name}))
      (subscribe-system {:system-name (:name (register-new-test-system))
                         :products [product-name]})))

  
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
      (val/duplicate-disallowed create-activation-key [{:name (uniqueify "auto-key")
                                                        :description "my description"
                                                        :environment test-environment}]))))

