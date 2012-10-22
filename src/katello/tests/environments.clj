(ns katello.tests.environments
  (:refer-clojure :exclude [fn])
  (:require (katello  [api-tasks :as api] 
                      [organizations :as organization] 
                      [ui-tasks :refer [navigate errtype]] 
                      [sync-management :as sync] 
                      [tasks :refer :all] 
                      [environments :as environment] 
                      [validation :refer :all] 
                      [client :as client]
                      [systems :refer :all]
                      [conf :as conf]) 
            [katello.tests.providers :refer [with-n-new-orgs]]
            [katello.client.provision :as provision]
            [test.tree.script :refer :all]
            [slingshot.slingshot :refer :all]
            [tools.verify :refer [verify-that]]
            [serializable.fn :refer [fn]]
            [clojure.string :refer [capitalize upper-case lower-case trim]]
            [bugzilla.checker :refer [open-bz-bugs]]
            [deltacloud :as cloud]))

;; Variables

(def test-org-name (atom nil))
(def first-env "dev")

;; Functions

(defn verify-delete-env-restricted-to-this-org
  "Verify that when you create multiple environments with the same
   name in multiple orgs, and you delete one enviroment, the
   environment in the other orgs are not affected. Does the
   verification by navigating to the remaining environment(s). Takes
   the environment name to use, and a list of orgs as arguments. The
   orgs must already exist. See also
   katello.tests.provider/with-n-new-orgs which will handle this
   automatically."
  [env-name orgs]
  (doseq [org orgs]
    (organization/switch org)
    (environment/create env-name {:org-name org}))
  (environment/delete env-name {:org-name (first orgs)})
  (doseq [org (rest orgs)]
    (navigate :named-environment-page {:env-name env-name
                                       :org-name org})))

(defn verify-create-same-env-in-multiple-orgs
  "Verifies that the same environment name can be used independently
   in different organizations. See also
   verify-delete-env-restricted-to-this-org."
  [env-name orgs]
  (doseq [org orgs]
    (organization/switch org)
    (environment/create env-name {:org-name org})))

(defn setup-environment-with-promoted-content
  "Creates a new environment in the admin org, and promotes a
   product with a sync'd repo into it. Uses the API."
  [env-name]
  (with-unique [provider-name  "prov"
                product-name  "prod"
                repo-name  "repo"]
    (api/create-environment env-name {})
    (api/create-provider provider-name)
    (api/create-product product-name {:provider-name provider-name})
    (api/create-repo repo-name {:product-name product-name :url (@conf/config :sync-repo)})
    (sync/perform-sync [repo-name])
    (api/with-env env-name
      (api/promote {:products [{:product_id (api/get-id-by-name :product product-name)}]}))))

;; Setup

(defn create-test-org
  "Creates a new org for testing environments
   using the API."
  []
  (api/create-organization
   (reset! test-org-name (uniqueify "env-test"))
   {:description "organization used to test environments."}))


;; Tests

(defgroup environment-tests
  :group-setup create-test-org

  (deftest "Create an environment"
    (environment/create (uniqueify "simple-env") {:org-name @test-org-name
                                                  :description "simple environment description"})

    (deftest "Create parallel sequential environments"
      :description "Creates two parallel environment paths: one with 5 environments and the
                      other with 2 environments, both off the Library."
      (let [envs1 (take 5 (unique-names "envpath1"))
            envs2 (take 2 (unique-names "envpath2"))
            org (@conf/config :admin-org)]
        (environment/create-path org envs1)
        (environment/create-path org envs2)))

    (deftest "Delete an environment"
      :blockers (open-bz-bugs "790246")

      (with-unique [env-name "delete-env"]
        (environment/create env-name {:org-name @test-org-name
                                      :description "simple environment description"})
        (environment/delete env-name {:org-name @test-org-name}))


      (deftest "Deleting an environment does not affect another org with the same name environment"
        (with-n-new-orgs 2 verify-delete-env-restricted-to-this-org))


      (deftest "Delete an environment that has had content promoted into it"
        :blockers api/katello-only

        (with-unique [env-name "del-w-content"]
          (setup-environment-with-promoted-content env-name)
          (environment/delete env-name {:org-name (@conf/config :admin-org)})))




      (deftest "Verify that only environments at the end of their path can be deleted"
        :description "Creates 3 environments in a single path, tries
                      to delete the middle one, which should fail.
                      Then tries to delete the last one, which should
                      succeed."
        :blockers    (open-bz-bugs "794799")

        (let [envs (take 3 (unique-names "env"))
              org (@conf/config :admin-org)]
          (environment/create-path org envs)
          (expecting-error [:type :env-cant-be-deleted]
                           (environment/delete (second envs) {:org-name org}))
          (environment/delete (last envs) {:org-name org}))))


    (deftest "Cannot create two environments in the same org with the same name"
      :blockers (open-bz-bugs "726724")

      (with-unique [env-name "test-dup"]
        (expecting-error-2nd-try (errtype :katello.notifications/name-must-be-unique-within-org)
          (environment/create env-name
                              {:org-name @test-org-name
                               :description "dup env description"}))))

    (deftest "Two environments with name that differs only in case are disalowed"
      :blockers (open-bz-bugs "847037")
      :data-driven true

      (fn [orig-name modify-case-fn]
        (expecting-error (errtype :katello.notifications/name-must-be-unique-within-org)
                         (with-unique [name orig-name]
                           (environment/create name {:org-name @test-org-name
                                                     :description "dup env description"})
                           (environment/create (modify-case-fn name) {:org-name @test-org-name
                                                                      :description "dup env description"}))))

      [["env"      capitalize]
       ["yourenv" capitalize]
       ["env"      upper-case]
       ["MyEnv"   upper-case]
       ["YOUREnv" lower-case]])
      
    (deftest "Rename an environment"
      :blockers (constantly ["Renaming is not supported for v1"])

      (with-unique [env-name  "rename"
                    new-name  "newname"]
        (environment/create env-name {:org-name @test-org-name
                                      :description "try to rename me!"})
        (environment/edit env-name {:org-name @test-org-name
                                    :new-name new-name})
        (navigate :named-environment-page
                  {:org-name @test-org-name
                   :env-name new-name})))


    (deftest "Create environments with the same name but in different orgs"
      (with-n-new-orgs 2 verify-create-same-env-in-multiple-orgs)))


  (deftest "Enviroment name is required"
    (expecting-error name-field-required
                     (environment/create nil {:org-name @test-org-name
                                              :description "env description"})))

  (deftest "Move systems from one env to another"
    :blockers conf/no-clients-defined
    
    (provision/with-client "envmovetest" ssh-conn
      (with-unique [env-dev  "dev"
                    env-test  "test"]
        (environment/create env-dev {:org-name @test-org-name})
        (environment/create env-test {:org-name @test-org-name})
        (client/setup-client ssh-conn)
        (client/register ssh-conn {:username conf/*session-user*
                                   :password conf/*session-password*
                                   :org @test-org-name
                                   :env env-dev
                                   :force true})
        (let [client-hostname (-> ssh-conn (client/run-cmd "hostname") :stdout trim)]
          (verify-that (= env-dev (get-system-env client-hostname)))
          (verify-that (client/does-system-belong-to-an-environment? ssh-conn client-hostname env-dev))
          (edit-system-environment (:name client-hostname) {:environment env-test})
          (verify-that (= env-test (get-system-env client-hostname)))
          (verify-that (client/does-system-belong-to-an-environment? ssh-conn client-hostname env-test)))))))
        
        
