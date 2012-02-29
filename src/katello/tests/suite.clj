(ns katello.tests.suite
  (:refer-clojure :exclude [fn])
  (:require (clojure [pprint :as pprint]
                     [zip :as zip]
                     [walk :as walk])

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
        slingshot.slingshot
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
                   
                   #_(comment "renaming disabled for v1"
                              {:name "rename an environment"
                               :steps envs/rename})

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
            :steps providers/upload-manifest
            :more [{:name "enable Red Hat repositories"
                    :steps providers/enable-redhat-repos
                    :more [{:name "client installs Red Hat content"
                            :steps e2e/client-access-redhat}]}]}]}])

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

(def to-trace ;;list of namespaces and fns we want to trace
  '[katello.tasks
    katello.api-tasks
    katello.client'test.tree/execute
    katello.tests.setup/start-selenium
    katello.tests.setup/stop-selenium
    katello.tests.setup/switch-new-admin-user
    com.redhat.qe.verify/check
    com.redhat.qe.auto.selenium.selenium/call-sel
    com.redhat.qe.config/property-map])

(def do-not-trace ;;set of fns to exclude from tracing
  #{'katello.tasks/notification 
    'katello.tasks/success?
    'katello.tasks/uniqueify
    'katello.tasks/unique-names
    'katello.tasks/timestamps})


(def test-traces (ref {}))

(defn wrap-tracing [runner]
  (fn [test]
    (println "tracing")
    (binding [tracer (fn [_ value & [out?]]
                       (dosync
                        (alter test-traces update-in [test]
                               (fn [v]
                                 (conj (or v (with-meta [] {:log true})) [value out?])))))]
      (let [result (runner test)]
        (if (-> (:result result) (= :fail))
          (assoc-in  result [:error :trace] (@test-traces test))
          result)))))

(defn mktree [vz [i out? :as e]]
  (if out?
    (-> vz (zip/append-child i) zip/up )
    (-> vz (zip/append-child [i]) zip/down zip/rightmost)))

(defn to-html-snippet [tracelist]
  (-> (reduce mktree (zip/vector-zip []) tracelist)
     zip/root
     ))

(comment "test tracing data -ignore"
         (def mytraceout
           (with-meta '
             [[(+ 5 (- 4 2 (* 9 3)) (* 5 (+ 6 3))) nil]
              [(- 4 2 (* 9 3)) nil]
              [(* 9 3) nil]
              [27 true]
              [-25 true]
              [(* 5 (+ 6 3)) nil]
              [(+ 6 3) nil]
              [9 true]
              [45 true]
              [25 true]]
             {:log true})))



(defn -main [ & args]
  (println args)
  (alter-var-root #'test/runner (fn [_] (-> test/execute
                                          wrap-tracing
                                          test/wrap-blockers
                                          test/wrap-timer
                                          test/wrap-data-driven)))
  
  (binding [tracer (per-thread-tracer text-format)
            *print-level* 10
            *print-length* 30 
            pprint/*print-pprint-dispatch* log-dispatch
            report/syntax-highlight (report/syntax-highlighter
                                     "http://hudson.rhq.lab.eng.bos.redhat.com:8080/shared/syntaxhighlighter/")]
    (dotrace (remove do-not-trace (all-fns to-trace)) 
      (let [reports (test/run-suite (suite))]
        (println "----- Blockers -----\n ")
        (let [blockers (->> reports
                          vals
                          (mapcat #(get-in % [:report :blocked-by]))
                          (filter #(not (nil? %)))
                          frequencies)]
          (pprint/pprint blockers))))

    ;;convert trace files to pretty html
    (comment(htmlify "html"  (->> (System/getProperty "user.dir")
                                java.io.File.
                                .listFiles
                                seq
                                (filter #(-> % .getName (.endsWith ".trace")))
                                (map #(.getCanonicalPath %)))
             
                     "http://hudson.rhq.lab.eng.bos.redhat.com:8080/shared/syntaxhighlighter/"))))


