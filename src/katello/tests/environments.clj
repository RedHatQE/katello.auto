(ns katello.tests.environments
  (:refer-clojure :exclude [fn])
  (:require katello
            (katello [navigation :as nav]
                     [rest :as rest]
                     [ui :as ui]
                     [organizations :as organization] 
                     [ui-common :refer [errtype]] 
                     [sync-management :as sync] 
                     [tasks :refer :all] 
                     [environments :as environment]
                     [notifications :as notification]
                     [systems :as system]
                     [validation :refer :all] 
                     [client :as client] 
                     [conf :as conf]
                     [changesets :as changeset]) 
            [katello.tests.providers :refer [with-n-new-orgs]]
            [katello.client.provision :as provision]
            [test.tree.script :refer :all]
            [test.assert :as assert]
            [serializable.fn :refer [fn]]
            [clojure.string :refer [capitalize upper-case lower-case trim]]
            [bugzilla.checker :refer [open-bz-bugs]]))

;; Variables

(def test-org (atom nil))
(def first-env "dev")

;; Functions
(defn create-same-env-in-multiple-orgs
  "Verifies that the same environment name can be used independently
   in different organizations. See also
   verify-delete-env-restricted-to-this-org."
  [envs]
  {:pre [(apply = (map :name envs))  ; names all same
         (= (count envs)
            (count (distinct (map :org envs))))]} ; orgs all different
  (doseq [env envs]
    (ui/create env)))

(defn verify-delete-env-restricted-to-this-org
  "Verify that when you create multiple environments with the same
   name in multiple orgs, and you delete one enviroment, the
   environment in the other orgs are not affected. Does the
   verification by navigating to the remaining environment(s). Takes
   the environment name to use, and a list of orgs as arguments. The
   orgs must already exist. See also
   katello.tests.provider/with-n-new-orgs which will handle this
   automatically."
  [envs]
  (create-same-env-in-multiple-orgs envs)
  (ui/delete (first envs))
  (doseq [env (rest envs)]
    (nav/go-to ::environment/named-page {:env-name (:name env)
                                         :org-name (-> env :org :name)})))



(defn setup-with-promoted-content
  "Creates a new environment in the admin org, and promotes a
   product with a sync'd repo into it. Uses the API."
  [env]
  (with-unique [prov (katello/newProvider {:name "prov"})
                prod (katello/newProduct {:name "prod" :provider prov})
                repo (katello/newRepository {:name "repo" :product prod
                                             :url (@conf/config :sync-repo)})]
    (doseq [ent (list prov prod repo)]
      (rest/create ent))
    (let [content (list repo)]
      (sync/perform-sync content)
      (changeset/api-promote env content))))

;; Setup

(defn create-test-org
  "Creates a new org for testing environments
   using the API."
  []
  (reset! test-org-name (-> {:name "env-test"
                             :description "organization used to test environments."}
                            katello/newOrganization
                            uniqueify
                            rest/create)))


;; Tests

(defgroup environment-tests
  :group-setup create-test-org

  (deftest "Create an environment"
    (-> {:name "simple-env"
         :org @test-org}
        katello/newEnvironment
        uniqueify
        ui/create)
    

    (deftest "Create parallel sequential environments"
      :description "Creates two parallel environment paths: one with 5 environments and the
                      other with 2 environments, both off the Library."

      (doseq [[basename thismany] [["envpath1" 2]
                                   ["envpath2" 5]]]
        (->> {:name basename
              :org (@conf/config :admin-org)}
             katello/newEnvironment
             uniques
             (take thismany)
             environment/create-path)))

    (deftest "Delete an environment"
      :blockers (open-bz-bugs "790246")

      (with-unique [env (katello/newEnvironment {:name "delete-env"
                                                 :org @test-org-name
                                                 :description "simple environment description"})]
        (ui/create env)
        (ui/delete env))


      (deftest "Deleting an environment does not affect another org with the same name environment"
        (with-n-new-orgs 2 verify-delete-env-restricted-to-this-org))


      (deftest "Delete an environment that has had content promoted into it"
        :blockers api/katello-only

        (with-unique [env (katello/newEnvironment {:name "del-w-content"
                                                   :org (@conf/config :admin-org)})]
          (setup-with-promoted-content env)
          (ui/delete env)))

      (deftest "Verify that only environments at the end of their path can be deleted"
        :description "Creates 3 environments in a single path, tries
                      to delete the middle one, which should fail.
                      Then tries to delete the last one, which should
                      succeed."
        :blockers    (open-bz-bugs "794799")

        (let [envs (take 3 (uniques (katello/newEnvironment {:name "env"
                                                             :org (@conf/config :admin-org)})))]
          (environment/create-path envs)
          (expecting-error [:type ::environment/cant-be-deleted]
                           (ui/delete (second envs)))
          (ui/delete (last envs)))))


    (deftest "Cannot create two environments in the same org with the same name"
      :blockers (open-bz-bugs "726724")

      (with-unique [env (katello/newEnvironment {:name "test-dup"
                                                 :org @test-org-name
                                                 :description "dup env description"})]
        (expecting-error-2nd-try (errtype ::notification/env-name-must-be-unique-within-org)
                                 (ui/create env))))
      
    (deftest "Edit an environment description"
     
      (with-unique [env  (katello/newEnvironment {:name "edit"
                                                  :org @test-org-name
                                                  :description "try to change me!"})]
        (ui/create env)
        (ui/update env assoc :description "I changed it!")))

    (deftest "Create environments with the same name but in different orgs"
      (with-n-new-orgs 2 create-same-env-in-multiple-orgs))
    
    (deftest "Adding environment named or labeled 'Library' is disallowed"
      :data-driven true

      (fn [env-name env-label notif] 
        (organization/switch @test-org-name)
        (let [env (katello/newEnvironment {:name env-name
                                           :label env-label
                                           :org @test-org-name})]
          (expecting-error (errtype notif)
                           (ui/create env))))

      [["Library" "Library" ::notification/env-name-lib-is-builtin]
       ["Library" "Library" ::notification/env-label-lib-is-builtin]
       ["Library" (uniqueify "env-lbl") ::notification/env-name-lib-is-builtin]]))


  (deftest "Enviroment name is required"
    (expecting-error name-field-required
                     (ui/create (katello/newEnvironment {:org-name @test-org-name
                                                         :description "env description"}))))

  (deftest "Move systems from one env to another"
    :blockers conf/no-clients-defined
    
    (provision/with-client "envmovetest" ssh-conn
      (with-unique [env-dev  (katello/newEnvironment {:name "dev"
                                                      :org @test-org-name})
                    env-test (katello/newEnvironment {:name "test"
                                                      :org @test-org-name})]
        (doseq [env [env-dev env-test]]
          (ui/create env))
        (organization/switch @test-org-name)
        (client/setup-client ssh-conn)
        (client/register ssh-conn {:username conf/*session-user*
                                   :password conf/*session-password*
                                   :org @test-org-name
                                   :env env-dev
                                   :force true})
        (let [client-hostname (-> ssh-conn (client/run-cmd "hostname") :stdout trim)]
          (assert/is (= (:name env-dev) (system/environment client-hostname)))
          (system/set-environment client-hostname env-test)
          (assert/is (= (:name env-test) (system/environment client-hostname)))
          (client/sm-cmd ssh-conn :refresh)
          (client/run-cmd ssh-conn "yum repolist")
          ;;verify the env name is now in the urls in redhat.repo
          ;;we haven't subscribed to anything so the below
          ;;verification doesn't work - repo file will be empty
          #_(let [cmd (format "grep %s /etc/yum.repos.d/redhat.repo" env-test)
                  result (client/run-cmd ssh-conn cmd)]
              (assert/is (->> result :exit-code (= 0)))))))))
        
        
