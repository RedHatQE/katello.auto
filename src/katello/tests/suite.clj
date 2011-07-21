(ns katello.tests.suite
  (:refer-clojure :exclude [fn])
  (:require
   (katello [tasks :as tasks]
            [api-tasks :as api]
            [validation :as validate])
   (katello.tests [setup :as setup]
                  [providers :as providers]
                  [promotions :as promotions]
                  [sync_management :as sync])
   [com.redhat.qe.auto.selenium.selenium :as sel]
   [test-clj.core :as test]
   [clojure.contrib.trace :as trace])
  (:use [test-clj.core :only [fn]]
        (katello [trace :only [dotrace-all]]
                 [conf :only [config]])
        [com.redhat.qe.verify :only [verify-that check]]
        [com.redhat.qe.auto.bz :only [blocked-by-bz-bugs]]))

(declare login-tests org-tests environment-tests provider-tests)

(defn suite []
  {:name "startup"
   :configuration true
   :steps (fn [] (setup/start-sel))
   :more (conj (login-tests)
               {:name "shut down"
                :configuration :true
                :steps (fn [] (setup/stop-selenium))})})

(defn login-tests []
  [{:name "login as admin"
    :steps (fn [] (tasks/verify-success
                  #(tasks/login (@config :admin-user)
                                (@config :admin-password))))
    :more (concat (org-tests)
                  (provider-tests)
                  (sync/tests)
                  (promotions/tests))}

   {:name "login as invalid user"
    :pre (constantly true) ;;disables test
    :steps (fn [] (tasks/login "invalid" "asdf1234"))}])

(def test-org-name (atom nil))

(defn org-tests []
  [{:name "create an org"
    :steps (fn [] (tasks/verify-success
                  #(tasks/create-organization
                    (tasks/uniqueify "auto-org") "org description")))
    :more (concat
           [{:name "delete an org"
             :pre (blocked-by-bz-bugs "716972")
             :steps (fn []
                      (let [org-name (tasks/uniqueify "auto-del")]
                        (tasks/create-organization org-name "org to delete immediately")
                        (tasks/delete-organization org-name)
                        (let [remaining-org-names (doall (map :name (api/all-entities :organization)))]
                          (verify-that (not (some #{org-name} remaining-org-names))))))}
            {:name "duplicate org disallowed"
             :steps (fn []
                      (let [org-name (tasks/uniqueify "test-dup")]
                        (validate/duplicate_disallowed
                         #(tasks/create-organization org-name "org-description"))))}
            {:name "name required"
             :steps (fn []
                      (validate/name-field-required
                       #(tasks/create-organization nil "org description")))}]
           
           (test/data-driven {:name "org valid name"}
                             (fn [name expected-error]
                               (validate/field-validation
                                #(tasks/create-organization name "org description")
                                expected-error))
                             (concat 
                              (validate/variations [:invalid-character
                                                    :name-must-not-contain-characters])
                              (validate/variations [:trailing-whitespace
                                                    :name-no-leading-trailing-whitespace])))
           (environment-tests))}])

(defn environment-tests []
  [{:configuration true
    :name "create a test org"
    :steps (fn [] (api/create-organization
                  (reset! test-org-name (tasks/uniqueify "env-test"))
                  "organization used to test environments."))
    :more [{:name "create environment"
            :pre (blocked-by-bz-bugs "693797" "707274")
            :steps (fn [] (tasks/verify-success
                          #(tasks/create-environment @test-org-name
                                                     (tasks/uniqueify "simple-env")
                                                     "simple environment description")))
            :more [{:name "delete environment"
                    :steps (fn [] (let [env-name (tasks/uniqueify "delete-env")]
                                   (tasks/create-environment
                                    @test-org-name
                                    env-name
                                    "simple environment description")
                                   (tasks/verify-success
                                    #(tasks/delete-environment @test-org-name env-name))))}
                   {:name "duplicate environment disallowed"
                    :steps (fn [] (let [env-name (tasks/uniqueify "test-dup")]
                                   (validate/duplicate_disallowed
                                    #(tasks/create-environment
                                      @test-org-name env-name "dup env description")
                                    :expected-error :name-must-be-unique-within-org)))}
                   {:name "rename an environment"
                    :steps (fn [] (let [env-name (tasks/uniqueify "rename")
                                       new-name (tasks/uniqueify "newname")]
                                   (tasks/create-environment @test-org-name
                                                             env-name
                                                             "try to rename me!")
                                   (tasks/edit-environment @test-org-name
                                                           env-name
                                                           :new-name
                                                           new-name)
                                   (tasks/navigate :named-environment-page
                                                   {:org-name @test-org-name
                                                    :env-name new-name})))}]}]}])

(defn provider-tests []
  [{:name "create a custom provider"
     :steps (fn [] (providers/test-provider :custom))
     :more (concat
            [{:name "rename a provider"
              :steps (fn [] (let [old-name (tasks/uniqueify "rename")
                                 new-name (tasks/uniqueify "newname")]
                             (tasks/create-provider old-name "my description" :custom)
                             (tasks/edit-provider old-name :new-name new-name)
                             (let [current-providers (map :name (api/all-entities
                                                                 :provider
                                                                 "ACME_Corporation"))]
                               (verify-that (and (some #{new-name} current-providers)
                                                 (not (some #{old-name} current-providers)))))))}]
            (test/data-driven {:name "provider validation"}
                              (fn  [name description repo-url type  expected-result]
                                (let [name (if (fn? name) (name) name)] ; uniqueifying at compile time defeats purpose of unique names
                                  (validate/field-validation       
                                   (fn []                           
                                     (tasks/create-provider name description type repo-url) 
                                     :success) expected-result)))
                              (concat
                               [[nil "blah" "http://sdf.com" :redhat :name-cant-be-blank]
                                
                                ^{:pre (blocked-by-bz-bugs "703528")
                                  :description "Test that invalid URL is rejected."}
                                [#(tasks/uniqueify "mytestcp") "blah" "@$#%$%&%*()[]{}" :redhat :repository-url-invalid]
                                ^{:pre (blocked-by-bz-bugs "703528")
                                  :description "Test that invalid URL is rejected."}
                                [#(tasks/uniqueify "mytestcp") "blah" "https://" :redhat :repository-url-invalid]
                                [#(tasks/uniqueify "mytestcp") "blah" "@$#%$%&%*(" :redhat :repository-url-invalid]

                                [#(tasks/uniqueify "mytestcp2") "blah" nil :redhat :repository-url-cant-be-blank]
                                [#(tasks/uniqueify "mytestcp3") nil "http://sdf.com" :redhat :only-one-redhat-provider-per-org]
                                [#(tasks/uniqueify "mytestcp4") nil "http://sdf.com" :custom :success]]
                               (validate/variations
                                [#(tasks/uniqueify "mytestcp5") :javascript "http://sdf.com" :custom :success])
                               (validate/variations                  
                                [:trailing-whitespace nil  "http://sdf.com" :custom  :name-no-leading-trailing-whitespace])
                               (validate/variations
                                [:invalid-character nil "http://sdf.com" :custom :name-must-not-contain-characters]))))}])



(defn -main [ & args]
  (binding [clojure.contrib.trace/tracer
            (fn [name value]
              (println (str (when name (format "%6s:" name))  value)))]
    (dotrace-all [katello.tasks katello.api-tasks]
                 [test/execute check] []
                 (test/run-suite (suite)))))
