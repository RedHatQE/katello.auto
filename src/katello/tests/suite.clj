(ns katello.tests.suite
  (:refer-clojure :exclude [fn])
  (:require (clojure [pprint :as pprint])

            (katello.tests [setup :as setup]
                           [organizations :as orgs]
                           [providers :as providers]
                           [promotions :as promotions]
                           [sync_management :as sync]
                           [login :as login]
                           [environments :as envs]
                           [systems :as systems]
                           [users :as users]
                           [permissions :as permissions]
                           [templates :as templates]
                           [e2e :as e2e])
            
            (katello [tasks :as tasks]
                     [conf :as conf] 
                     [validation :as validate]
                     [locators :as locators])

            [test.tree :as test]
            [test.tree.reporter :as report]

            [com.redhat.qe.auto.selenium.selenium :as selenium]
            com.redhat.qe.config)
  (:use test.tree.builder
        fn.trace
        [serializable.fn :only [fn]]
        [com.redhat.qe.auto.bz :only [open-bz-bugs]]))

;;suite file - outlines a tree of tests where the deeper into the tree
;;you go, the more detailed the tests are.  Each test depends on its
;;parent test.

(declare nav-tests org-tests environment-tests provider-tests
         system-tests user-tests sync-tests permission-tests template-tests end-to-end-tests)

(defn ^:dynamic tests-to-run []
  (concat (nav-tests)
          (user-tests)
          (permission-tests)
          (org-tests)
          (provider-tests)
          (sync-tests)
          (promotions/tests)
          (system-tests)         
          (template-tests)
          (end-to-end-tests)
          (data-driven {:name "login as invalid user"
                        :steps login/invalid
                        :blockers (open-bz-bugs "730738")} 
                       login/invalid-logins)))

(defn suite []
  (with-meta
    (before-all
     (fn [& _] (tasks/navigate :top-level))
     {:name "login as admin"
      :steps login/admin
      :more (tests-to-run)})
    (merge {:threads (let [user-choice (try (-> (System/getProperty "test.tree.threads")
                                               (Integer.))
                                            (catch Exception e 3))]
                       (Math/min user-choice 5))}
           setup/runner-config)))

(defn nav-tests []
  (data-driven {:name "navigate to tab"
                :steps (fn [tab]
                         (tasks/navigate tab)
                         (tasks/check-for-error 2000))}
               (map vector locators/tab-list)))

(defn org-tests []
  [{:name "create an org"
    :steps orgs/create
    :more (concat
           [{:name "delete an org"
             :blockers (open-bz-bugs "716972")
             :steps orgs/delete
             :more [{:name "verify cleanup of deleted org"
                     :steps orgs/recreate-org-with-same-data
                     :blockers (open-bz-bugs "771957")}]}
            
            {:name "duplicate org disallowed"
             :blockers (open-bz-bugs "726724")
             :steps orgs/dupe-disallowed}

            {:name "org name required"
             :blockers (open-bz-bugs "726724")
             :steps orgs/name-required}

            {:name "edit an org"
             :steps orgs/edit}
            
            {:name "search for org"
             :blockers (open-bz-bugs "750120")
             :steps orgs/search-org}]
           
           (data-driven {:name "org valid name"
                         :blockers (open-bz-bugs "726724")
                         :steps orgs/valid-name}
                        orgs/valid-name-data)
           
           (environment-tests))}])

(defn environment-tests []
  [{:configuration true
    :name "create a test org"
    :steps envs/create-test-org
    :more [{:name "create environment"
            :blockers (open-bz-bugs "693797" "707274")
            :steps envs/create
            :more [{:name "delete environment"
                    :steps envs/delete
                    :more [{:name "delete environment same name different org"
                            :description "Creates the same env name in two different orgs, deletes one and verifies the other still exists."
                            :steps envs/delete-same-name-diff-org}

                           {:name "delete environment with promoted content"
                            :steps envs/delete-env-with-promoted-content
                            :blockers (open-bz-bugs "790246")}

                           {:name "delete environment from middle of chain"
                            :description "Delete an environment from the middle of the chain and try to recreate the end of the chain"
                            :steps envs/delete-middle-env
                            :blockers (open-bz-bugs "794799")}]}
                   
                   {:name "duplicate environment disallowed"
                    :blockers (open-bz-bugs "726724")
                    :steps envs/dupe-disallowed}
                   
                   {:name "rename an environment"
                    :steps envs/rename}

                   {:name "environment namespace limited to org"
                    :steps envs/create-same-name-diff-org}]}

           {:name "environment name required"
            :blockers (open-bz-bugs "726724")
            :steps envs/name-required}]}])

(defn provider-tests []
  [{:name "create a custom provider"
    :steps providers/create-custom
    :more (concat
           [{:name "duplicate provider disallowed"
             :steps providers/dupe-disallowed}
            
            {:name "rename a provider"
             :steps providers/rename}

            {:name "delete a provider"
             :steps providers/delete}

            {:name "provider namespace limited to org"
             :steps providers/namespace-provider}
            
            {:configuration true
             :name "create provider for testing products and repos"
             :steps providers/setup-custom
             :blockers (open-bz-bugs "751910")
             :more [{:name "create a product"
                     :steps providers/create-product
                     :more [{:name "delete a product"
                             :steps providers/delete-product
                             :blockers (open-bz-bugs "729364")}
                            
                            {:name "create a repository"
                             :steps providers/create-repo
                             :blockers (open-bz-bugs "729364")
                             :more [{:name "delete a repository"
                                     :steps providers/delete-repo
                                     :blockers (open-bz-bugs "745279")}]}
                            
                            {:name "product namespace limited to org"
                             :steps providers/namespace-product-in-org
                             :blockers (open-bz-bugs "784712")}

                            {:name "same product name in different providers disallowed"
                             :steps providers/namespace-product-in-provider}]}]}]
           
           (data-driven {:name "provider validation"
                         :steps providers/validation}
                        (providers/validation-data)))}
   {:name "get latest subscription manifest"
    :steps providers/manifest-setup
    :configuration true
    :blockers (open-bz-bugs "729364")
    :more [{:name "upload subscription manifest"
            :blockers providers/manifest-testing-blockers
            :steps providers/upload-manifest}]}])

(defn sync-tests []
  [{:name "set up sync tests"
    :steps sync/setup
    :configuration true
    :more [{:name "simple sync"
            :description "Sync a product with just a few packages in one repo."
            :blockers (open-bz-bugs "705355" "711105" "712318" "715004" "727674" "727627" "790246")
            :steps sync/simple}
           
           {:name "create a sync plan"
            :steps sync/create-plan
            :blockers (open-bz-bugs "729364")
            :more (concat [{:name "edit a sync plan"
                            :steps sync/edit-plan}
                           
                           {:name "rename a sync plan"
                            :steps sync/rename-plan}
                           
                           {:name "duplicate sync plan disallowed"
                            :steps sync/dupe-disallowed}

                           {:name "assign sync plan to multiple products"
                            :steps sync/set-schedules
                            :blockers (open-bz-bugs "751876")
                            :more [{:name "reassign product sync plan"
                                    :steps sync/reset-schedule}]}]
                          
                          (data-driven {:name "sync plan validation"
                                        :steps sync/plan-validate}
                                       (sync/plan-validation-data)))}]}])

(defn system-tests []
  [{:name "setup environment for systems"
    :configuration true
    :steps systems/create-env
    :blockers (open-bz-bugs "717408" "728357")
    :more [{:name "rename a system"
            :description "Adds a system via REST api and then renames it in the UI"
            :blockers (open-bz-bugs "729364")
            :steps systems/rename}
           
           {:name "system appears on environment page"
            :description "Registers a system to an environment, and verifies it appears
                          on the Systems/Registered/Environments/[environment] page."
            :blockers (open-bz-bugs "738054")
            :steps systems/in-env}
           
           {:name "subscribe a system to a product"
            :blockers (open-bz-bugs "733780" "736547" "784701")
            :steps systems/subscribe}

           {:name "create an activation key"
            :blockers (open-bz-bugs "750354")
            :steps systems/create-activation-key
            :more [{:name "delete an activation key"
                    :steps systems/remove-activation-key}

                   {:name "duplicate activation key disallowed"
                    :steps systems/activation-key-dupe-disallowed}]}]}])

(defn user-tests []
  [{:name "create a user"
    :steps users/create
    :more [{:name "edit a user"
            :blockers (open-bz-bugs "720469")
            :steps users/edit}

           {:name "delete a user"
            :steps users/delete}

           {:name "duplicate user disallowed"
            :steps users/dupe-disallowed
            :blockers (open-bz-bugs "738425")}

           {:name "users' miniumum password length enforced"
            :steps users/min-password-length}
           
           {:name "search for user"
            :steps users/search-users}

           {:name "assign role to user"
            :steps users/assign-role}]}])

(defn permission-tests []
  [{:name "create a role"
    :steps permissions/create-role
    :more [{:name "delete a role"
            :steps permissions/remove-role}

           {:name "add permission and user to a role"
            :steps permissions/edit-role
            :more (data-driven {:name "user with permissions is properly restricted"
                                :steps permissions/verify-access}
                               permissions/access-test-data)}]}])

(defn template-tests []
  [{:name "create a system template"
    :steps templates/create
    :more [{:name "setup template content"
            :configuration true
            :steps templates/setup-content
            :blockers (open-bz-bugs "765888")
            :more [{:name "add products to template"
                    :steps templates/add-content}]}]}])

(defn end-to-end-tests []
  [{:name "client installs custom content"
    :steps e2e/client-access-custom 
    
    ;;the test.tree feature to filter on passed? is broken, can't
    ;;figure out why.  
    ;;:blockers (filter-tests (every-pred (named? ["simple sync"
    ;;                                              "promote content"])
    ;;                           (complement report/passed?)))
    :blockers (open-bz-bugs "784853" "790246")
    }])

(def fns-to-trace ;;list of namespaces and fns we want to trace
  {:namespaces ['katello.tasks
                'katello.api-tasks
                'katello.client]
   :fns ['test.tree/execute
         'katello.tests.setup/start-selenium
         'katello.tests.setup/stop-selenium
         'katello.tests.setup/switch-new-admin-user
         'com.redhat.qe.verify/check
         'com.redhat.qe.auto.selenium.selenium/call-sel
         'com.redhat.qe.config/property-map]
   :exclude ['katello.tasks/notification 
             'katello.tasks/success?
             'katello.tasks/uniqueify
             'katello.tasks/unique-names
             'katello.tasks/timestamps]})

(defn -main [ & args]
  (println args)
  (binding [tracer (per-thread-tracer clj-format)
            *print-level* 10
            *print-length* 30]
    (dotrace-all fns-to-trace 
      (let [reports (test/run-suite (suite))]
        (println "----- Blockers -----\n ")
        (let [blockers (->> reports
                          vals
                          (mapcat #(get-in % [:report :blocked-by]))
                          (filter #(not (nil? %)))
                          frequencies)]
          (pprint/pprint blockers))))

    ;;convert trace files to pretty html
    (htmlify "html"  (->> (System/getProperty "user.dir")
                        java.io.File.
                        .listFiles
                        seq
                        (filter #(-> % .getName (.endsWith ".trace")))
                        (map #(.getCanonicalPath %)))
             
             "http://hudson.rhq.lab.eng.bos.redhat.com:8080/shared/syntaxhighlighter/")))

