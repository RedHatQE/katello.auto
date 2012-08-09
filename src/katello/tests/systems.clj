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

(defmacro defstep
  "Creates a simple step function where the return value does not need
   to be kept. Step functions can be threaded together to all use the
   same input map. Each step function has a list of relevant keys in
   the map that it cares about (ones that are referred to in the body
   expression). When threaded together, each step function will be
   executed and then call the next step (if any). The options arg is
   for steps where the relevant key might change depending on how the
   step is used. For example, you might have a step to copy something
   and step to delete something. The step to delete might be used to
   delete the original or the copy. The body can then refer to any of
   the symbols in the options list."
  [stepname docstring relevant-keys & body]
  `(defn ~stepname [continue-fn#]
     (fn [{:keys ~relevant-keys :as req#}] 
       ~@body
       (continue-fn# req#))))

(defstep step-add-new-system-to-new-group
    "Creates a system and system group, adds the system to the system group."
  [group-name system-name]
  (do (create-system system-name {:sockets "1"
                                  :system-arch "x86_64"})
      (create-system-group group-name {:description "rh system-group"})
      (add-to-system-group group-name system-name)))

(defn step-remove-system-group
  "Removes a system group given a request map. Optional arg
   which-group determines which key contains the group to remove.
   Defaults to :system-group."
  [continue-fn which-group]
  (fn [{:keys [system-group also-remove-systems?] :as req}]
    (remove-system-group (req which-group system-group)
                         {:also-remove-systems? also-remove-systems?})
    (continue-fn req)))

(defstep step-verify-system-presence ""
  [system-name also-remove-systems?] 
  (let [all-system-names (map :name (api/with-admin (api/all-entities :system)))]
    (if also-remove-systems?
      (verify-that (some #{system-name} all-system-names))
      (verify-that (not (some #{system-name} all-system-names))))))

(defstep step-copy-system-group ""
  [system-group copy-name]
  (copy-system-group system-group copy-name {:description "copied system group"}))

(def step0 (constantly {}))
(defmacro steps-> "Builds a request/response fn and calls it with map m."
  [m & steps]
  `((-> ~@(reverse (conj steps `step0))) ~m))

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
      (steps-> (uniqueify-vals
                {:system-name "mysystem"
                 :group-name "my-group"})
               step-add-new-system-to-new-group))


    (deftest "Delete a system group"
      :data-driven true

      (fn [data]
        (steps-> (merge data
                        (uniqueify-vals
                         {:system-name "mysystem"
                          :group-name "to-del"}))
                 step-create-system
                 step-create-group
                 step-add-system-to-group
                 step-remove-system-group
                 step-verify-system-presence))
      
      [[{:also-remove-systems? true}]
       [{:also-remove-systems? false}]])
    

    (deftest "Copy a system group"
      (steps-> (uniqueify-vals
                {:system-name  "mysystem"
                 :group-name  "copyme"
                 :copy-name  "imthecopy"})
               step-create-system
               step-create-group
               step-add-system-to-group
               step-copy-system-group)
      

      (deftest "Delete a copied system group"
        :data-driven true

        (fn [data]
          (steps-> (merge data (uniqueify-vals
                                {:system-name  "mysystem"
                                 :group-name  "to-del"
                                 :copy-name  "imthecopy"}))
                   step-create-system
                   step-create-group
                   step-add-system-to-group
                   step-copy-system-group
                   (step-remove-system-group :copy-name)))
      
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

