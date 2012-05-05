(ns katello.tests.systems
  (:refer-clojure :exclude [fn])
  (:use katello.tasks
        test.tree.script
        [serializable.fn :only [fn]]
        [katello.conf :only [config *environments*]]
        [com.redhat.qe.verify :only [verify-that]])
  (:require (katello [api-tasks :as api]
                     [validation :as val])))

;; Functions

(defn create-test-environment [] 
  (def test-environment (first *environments*))
  (api/with-admin
    (api/ensure-env-exist test-environment {:prior library})))

(defn register-new-test-system [env]
  (api/with-admin
    (api/with-env env
      (api/create-system (uniqueify "newsystem")
                         {:facts (api/random-facts)}))))

(defn verify-system-rename [system]
  (with-unique [new-name "yoursys"] 
    (edit-system (:name system) :new-name new-name)
    (navigate :named-systems-page {:system-name new-name})))

(defn verify-system-appears-on-env-page
   [system env]
  (navigate :named-system-environment-page
            {:env-name env
             :system-name (:name system)})
  (verify-that (= (:environment_id system)
                  (api/with-admin
                    (api/get-id-by-name :environment test-environment)))))

;; Tests


(defgroup all-system-tests
  :group-setup create-test-environment

  
  (deftest "Rename an existing system" 
    (-> test-environment
       register-new-test-system
       verify-system-rename))

  
  (deftest "Verify system appears on Systems By Environment page in its proper environment"
    (verify-system-appears-on-env-page (register-new-test-system)))

  
  (deftest "Subscribe a system to a product"
    (with-unique [provider-name "subscr-prov"
                  product-name "subscribe-me"]
      (api/with-admin
        (api/create-provider provider-name)
        (api/create-product product-name {:provider-name provider-name}))
      (subscribe-system {:system-name (:name (register-new-test-system))
                         :products [product-name]})))

  
  (deftest "Create an activation key" 
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

