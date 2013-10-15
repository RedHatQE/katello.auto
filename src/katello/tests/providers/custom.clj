(ns katello.tests.providers.custom
  (:require katello
            (katello [ui :as ui]
                     [providers :as provider]
                     [repositories :as repo]
                     [tasks :refer :all]
                     [validation :as val]
                     [sync-management :as sync]
                     [changesets :as changeset]
                     [organizations :as organization]
                     [content-view-definitions :as views]
                     [conf :as conf]
                     [blockers :refer [bz-bugs]])
            [test.tree.script :refer [defgroup deftest]]
            [katello.tests.useful :refer [create-recursive fresh-repo]]
            [test.assert :as assert]))
;; Tests

(defgroup custom-product-tests
  
  (deftest "Create a custom product"
    :uuid "c3099090-92af-0e04-6e83-10297ed9b2c9"
    (let [repo (fresh-repo conf/*session-org* "http://inecas.fedorapeople.org/fakerepos/zoo/")
          prd (katello/product repo)]
    (ui/create prd)))

    
  (deftest "Delete a custom product"
    :uuid "b9940780-8261-01a4-e83b-10ed847dae24"
    (let [repo (fresh-repo conf/*session-org* "http://inecas.fedorapeople.org/fakerepos/zoo/")
          prd (katello/product repo)]
      (ui/create prd)
      (ui/delete prd)))
  
  (deftest "Create a repository"
    :uuid "14b96ac6-1721-ff74-6d0b-7d3c8c77208c"
    :blockers (bz-bugs "729364")
    (let [repo (fresh-repo conf/*session-org* "http://inecas.fedorapeople.org/fakerepos/zoo/")]
      (ui/create (katello/product repo))
      (ui/create repo)))
  
  (deftest "Create a repository without repo_url"
    :uuid "2ccd6072-a6b9-4f3a-a1a6-1893c975b1e7"
    (let [repo (fresh-repo conf/*session-org* nil)]
      (ui/create (katello/product repo))
      (ui/create repo)))
    
  
  (deftest "Delete a repository"
    :uuid "fa0ed141-2252-fe84-1f03-a0594dbec893"
    (let [repo (fresh-repo conf/*session-org* "http://inecas.fedorapeople.org/fakerepos/zoo/")]
      (ui/create (katello/product repo))
      (ui/create repo)
      (ui/delete repo)))
  
  (deftest "Create two products with the same name, in different orgs"
    :uuid "925f72c2-1bd7-8024-0d33-1736ea23de14"
    :blockers (bz-bugs "784712" "802795")
    
    (with-unique [provider (katello/newProvider {:name "prov"})
                  product (katello/newProduct {:name "prod"
                                               :provider provider})]
      (doseq [org (->> {:name "prov-org"}
                       katello/newOrganization
                       uniques
                       (take 2))]
        (create-recursive (update-in product [:provider :org] (constantly org))))))
  
  
  (deftest "Create two products with same name in same org different provider"
    :uuid "bae46e69-8f9b-9474-9e53-ab8f6367c8c3"
    :description "Creates products with the same name in different
                    providers, where the providers are in the same
                    org."
    (with-unique [org (katello/newOrganization {:name "org"})
                  product (katello/newProduct {:name "prod"})]
      (doseq [prov (->> {:name "prov"
                         :org org}
                        katello/newProvider
                        uniques
                        (take 2))]
        (create-recursive (assoc product :provider prov)))))
  
  (deftest "Repository Autodiscovery for new product"
    :uuid "f45dcc7e-3fbd-3d84-1a43-15e26d6c5384"
    :description "Uses the repo autodiscovery tool to create custom repositories within a new custom product."
    (with-unique [org      (katello/newOrganization {:name "org"})
                  provider (katello/newProvider {:name "prov"
                                                 :org org})
                  provider-prd  (katello/newProduct  {:name "prod"
                                                      :provider provider})  ;; hacked so as to at-least test the UI navigation. Bug 1011473
                  product   (katello/newProduct  {:name "prod"
                                                  :provider provider})]
      (ui/create-all (list org provider-prd))
      (provider/create-discovered-repos-within-product product
                                                       "http://inecas.fedorapeople.org/fakerepos/" ["brew-repo/" "cds/content/nature/1.0/i386/rpms/"] {:new-prod true})))
  
  
  (deftest "Repository Autodiscovery for existing product"
    :uuid "cd24049d-9b47-9654-0f03-1c967d240d55"
    :description "Uses the repo autodiscovery tool to create custom repositories within an existing custom product."
    (with-unique [org      (katello/newOrganization {:name "org"})
                  provider (katello/newProvider {:name "prov"
                                                 :org org})
                  product  (katello/newProduct  {:name "prod"
                                                 :provider provider})]
      (ui/create-all (list org product))
      (provider/create-discovered-repos-within-product product
                                                       "http://inecas.fedorapeople.org/fakerepos/" ["brew-repo/" "cds/content/nature/1.0/i386/rpms/"])))
  
  (deftest "Sync and Promote a repository created via Repository Autodiscovery"
    :uuid "cd24049d-9b47-9654-0f03-1c967d240d55"
    (with-unique [org      (katello/newOrganization {:name "org"})
                  env      (katello/newEnvironment {:name "env", :org org})
                  provider (katello/newProvider {:name "prov"
                                                 :org org})
                  product  (katello/newProduct  {:name "prod"
                                                 :provider provider})]
      (ui/create-all (list org env product))
      (provider/create-discovered-repos-within-product product "http://inecas.fedorapeople.org/fakerepos/" ["brew-repo/"])
      (let [repo  (katello/newRepository {:name "brew-repo", :product product})
            cv    (-> {:name "content-view" :org org :published-name "publish-name"}
                      katello/newContentViewDefinition uniqueify)
            cs    (-> {:name "cs" :env env :content (list cv)}
                      katello/newChangeset uniqueify)]
        (sync/perform-sync (list repo))
        (sync/verify-all-repos-synced (list repo))
        (ui/create cv)
        (ui/update cv assoc :products (list product)
        (views/publish {:content-defn cv
                        :published-name (:published-name cv)
                        :description "test pub"
                        :org org})
        (changeset/promote-delete-content cs)))))
  
  (deftest "Auto-discovered repositories should automatically use GPG keys from product, if associated"
    :uuid "8129ec58-3013-2a74-0cb3-73bc4199c816"
    :blockers (bz-bugs "927335")
    (with-unique [org (katello/newOrganization {:name "org"})
                  provider (katello/newProvider {:name "prov"
                                                 :org org})
                  gpgkey (-> {:name "mykey", :org org,
                              :contents (slurp "http://inecas.fedorapeople.org/fakerepos/zoo/RPM-GPG-KEY-dummy-packages-generator")}
                             katello/newGPGKey
                             uniqueify)
                  product (katello/newProduct {:name "prod"
                                                 :provider provider
                                                 :gpg-key gpgkey})]
      (let [repo (katello/newRepository {:name "brew-repo"
                                         :product product})]
        (ui/create-all (list org gpgkey product))
        (provider/create-discovered-repos-within-product product
                                                       "http://inecas.fedorapeople.org/fakerepos/" [(format "%s/" (repo :name))])
        (assert/is (repo/gpgkey-associated? repo)))))
  
  (deftest "Auto-discovered repositories should not use GPG keys from product, unless associated"
    :uuid "b436d8d9-d663-1174-10b3-c01b7778e33f"
    (with-unique [org (katello/newOrganization {:name "org"})
                  provider (katello/newProvider {:name "prov"
                                                 :org org})
                  gpgkey (-> {:name "mykey", :org org,
                              :contents (slurp "http://inecas.fedorapeople.org/fakerepos/zoo/RPM-GPG-KEY-dummy-packages-generator")}
                             katello/newGPGKey
                             uniqueify)
                  product (katello/newProduct {:name "prod"
                                                 :provider provider})]
      (let [repo (katello/newRepository {:name "brew-repo"
                                         :product (assoc product :gpg-key gpgkey)})]
        (ui/create-all (list org gpgkey product))
        (provider/create-discovered-repos-within-product product
                                                       "http://inecas.fedorapeople.org/fakerepos/" [(format "%s/" (repo :name))])
        (assert/is (not (repo/gpgkey-associated? repo))))))
  
  (deftest "Add the same autodiscovered repo to a product twice"
    :uuid "340ec414-d857-d404-8653-58ee90756828"
    :description "Adds the repositories to the selected product twice."
    :blockers (bz-bugs "1013689")
    (with-unique [org (katello/newOrganization  {:name "org"})
                  provider (katello/newProvider {:name "prov"
                                                 :org org})
                  provider-prd  (katello/newProduct  {:name "prod"
                                                      :provider provider})
                  product (katello/newProduct   {:name "prod"
                                                 :provider provider})]
      (ui/create-all (list org provider-prd))
      (val/expecting-error-2nd-try (katello.ui-common/errtype :katello.notifications/label-taken-error)
                                   (provider/create-discovered-repos-within-product product
                                                                                    "http://inecas.fedorapeople.org/fakerepos/"  ["brew-repo/" "cds/content/nature/1.0/i386/rpms/"])))))