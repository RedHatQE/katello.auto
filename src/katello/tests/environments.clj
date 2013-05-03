(ns katello.tests.environments
  (:refer-clojure :exclude [fn])
  (:require [katello :as kt]
            (katello [navigation :as nav]
                     [rest :as rest]
                     [ui :as ui]
                     [organizations :as organization] 
                     [ui-common :refer [errtype]] 
                     [sync-management :as sync] 
                     [tasks :refer [uniques uniqueify with-unique expecting-error]] 
                     [environments :as environment]
                     [notifications :as notification]
                     [systems :as system]
                     [validation :refer :all] 
                     [client :as client] 
                     [conf :as conf]
                     [changesets :as changeset])
            [katello.tests.useful :refer [create-all-recursive]]
            [katello.client.provision :as provision]
            [test.tree.script :refer :all]
            [test.tree.builder :refer [union]]
            [test.assert :as assert]
            [serializable.fn :refer [fn]]
            [clojure.string :refer [capitalize upper-case lower-case trim]]
            [bugzilla.checker :refer [open-bz-bugs]]))

;; Variables

(def test-org (atom nil))

;; Functions

(defn create-same-env-in-multiple-orgs
  "Verifies that the same environment name can be used independently
   in different organizations. See also
   verify-delete-env-restricted-to-this-org."
  [envs]
  {:pre [(apply = (map :name envs))  ; names all same
         (= (count envs)
            (count (distinct (map :org envs))))]} ; orgs all different
  (create-all-recursive envs))

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
    (nav/go-to env)))



(defn setup-with-promoted-content
  "Creates a new environment in the admin org, and promotes a
   product with a sync'd repo into it. Uses the API."
  [env]
  (with-unique [prov (katello/newProvider {:name "prov" :org conf/*session-org*})
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
  (reset! test-org (-> {:name "env-test"
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
              :org @test-org}
             katello/newEnvironment
             uniques
             (take thismany)
             katello/chain
             ui/create-all)))

    (deftest "Delete an environment"
      :blockers (open-bz-bugs "790246")

      (with-unique [env (katello/newEnvironment {:name "delete-env"
                                                 :org @test-org
                                                 :description "simple environment description"})]
        (ui/create env)
        (ui/delete env))


      (deftest "Deleting an environment does not affect another org with the same name environment"
        (let [orgs (take 2 (uniques (kt/newOrganization {:name "delete-env-other"})))]
          (verify-delete-env-restricted-to-this-org
           (for [org orgs]
             (kt/newEnvironment {:name "same-name-diff-org"
                                 :org org})))))


      (deftest "Delete an environment that has had content promoted into it"
        :blockers rest/katello-only

        (with-unique [env (katello/newEnvironment {:name "del-w-content"
                                                   :org @test-org})]
          (setup-with-promoted-content env)
          (ui/delete env)))

      (deftest "Verify that only environments at the end of their path can be deleted"
        :description "Creates 3 environments in a single path, tries
                      to delete the middle one, which should fail.
                      Then tries to delete the last one, which should
                      succeed."
        :blockers    (open-bz-bugs "794799")

        (let [envs (take 3 (uniques (katello/newEnvironment {:name "env"
                                                             :org conf/*session-org*})))]
          (-> envs katello/chain ui/create-all)
          (expecting-error [:type ::environment/cant-be-deleted]
                           (ui/delete (second envs)))
          (ui/delete (last envs)))))


    (deftest "Cannot create two environments in the same org with the same name"
      :blockers (open-bz-bugs "726724")

      (with-unique [env (katello/newEnvironment {:name "test-dup"
                                                 :org @test-org
                                                 :description "dup env description"})]
        (expecting-error-2nd-try (errtype ::notification/env-name-must-be-unique-within-org)
                                 (ui/create env))))
      
    (deftest "Edit an environment description"
     
      (with-unique [env  (katello/newEnvironment {:name "edit"
                                                  :org @test-org
                                                  :description "try to change me!"})]
        (ui/create env)
        (ui/update env assoc :description "I changed it!")))

    (deftest "Create environments with the same name but in different orgs"
      (let [orgs (take 2 (uniques (kt/newOrganization {:name "delete-env-other"})))
            envs (for [org orgs]
                   (kt/newEnvironment {:name "same-name-diff-org"
                                       :org org}))]
        (ui/create-all (concat orgs envs))))
    
    (deftest "Adding environment named or labeled 'Library' is disallowed"
      :data-driven true

      (fn [env-name env-label notif] 
        (organization/switch @test-org)
        (let [env (katello/newEnvironment {:name env-name
                                           :label env-label
                                           :org @test-org})]
          (expecting-error (errtype notif)
                           (ui/create env))))

      [["Library" "Library" ::notification/env-name-lib-is-builtin]
       ["Library" "Library" ::notification/env-label-lib-is-builtin]
       ["Library" (uniqueify "env-lbl") ::notification/env-name-lib-is-builtin]]))


  (deftest "Enviroment name is required"
    (expecting-error name-field-required
                     (ui/create (katello/newEnvironment {:name ""
                                                         :org @test-org
                                                         :description "env description"}))))

  (deftest "Move systems from one env to another"
    :blockers (union (open-bz-bugs "959211")
                     conf/no-clients-defined)
    
    (provision/with-client "envmovetest" ssh-conn
      (with-unique [env-dev  (katello/newEnvironment {:name "dev"
                                                      :org @test-org})
                    env-test (katello/newEnvironment {:name "test"
                                                      :org @test-org})]
        (ui/create-all (list env-dev env-test))
        (client/register ssh-conn {:username (:name conf/*session-user*)
                                   :password (:password conf/*session-user*)
                                   :org (:name @test-org)
                                   :env (:name env-dev)
                                   :force true})
        (let [system (katello/newSystem {:name (-> ssh-conn
                                                   (client/run-cmd "hostname")
                                                   :stdout
                                                   trim)})
              names= (fn [& args] (apply = (map :name args)))]
          (assert/is (names= env-dev (system/environment system)))
          (ui/update system assoc :env env-test)
          (assert/is (= env-test (system/environment system)))
          (client/sm-cmd ssh-conn :refresh)
          (client/run-cmd ssh-conn "yum repolist")
          ;;verify the env name is now in the urls in redhat.repo
          ;;we haven't subscribed to anything so the below
          ;;verification doesn't work - repo file will be empty
          #_(let [cmd (format "grep %s /etc/yum.repos.d/redhat.repo" env-test)
                  result (client/run-cmd ssh-conn cmd)]
              (assert/is (->> result :exit-code (= 0)))))))))
