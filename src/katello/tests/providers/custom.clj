(ns katello.tests.providers.custom
  (:require katello
            (katello [ui :as ui]
                     [providers :as provider]
                     [repositories :as repo]
                     [tasks :refer :all]
                     [validation :as val]
                     [organizations :as organization]
                     [conf :as conf]
                     [blockers :refer [bz-bugs]])
            [test.tree.script :refer [defgroup deftest]]
            [katello.tests.useful :refer [create-recursive]]
            [test.assert :as assert]))

;; Variables

(def test-provider (atom nil))
(def test-product (atom nil))


;; Functions

(defn create-test-provider
  "Sets up a test custom provider to be used by other tests."
  []
  (ui/create (reset! test-provider
                     (uniqueify (katello/newProvider {:name "cust"
                                                      :description "my description"
                                                      :org conf/*session-org*})))))

;; Tests

(defgroup custom-product-tests
  :group-setup create-test-provider
  :blockers (bz-bugs "751910")
  
  (deftest "Create a custom product"
    :uuid "c3099090-92af-0e04-6e83-10297ed9b2c9"
    (ui/create (reset! test-product
                       (uniqueify (katello/newProduct {:provider @test-provider
                                                       :name "prod"
                                                       :description "test product"})))))

    
  (deftest "Delete a custom product"
    :uuid "b9940780-8261-01a4-e83b-10ed847dae24"
    :blockers (bz-bugs "729364")
    
    (doto (uniqueify (katello/newProduct {:provider @test-provider
                                          :name "deleteme"
                                          :description "test product to delete"}))
      (ui/create)
      (ui/delete)))
  
  (deftest "Create a repository"
    :uuid "14b96ac6-1721-ff74-6d0b-7d3c8c77208c"
    :blockers (bz-bugs "729364")
    
    (-> {:name "repo"
         :url "http://test.com/myurl"
         :product @test-product}
        katello/newRepository
        uniqueify
        ui/create))
  
  (deftest "Delete a repository"
    :uuid "fa0ed141-2252-fe84-1f03-a0594dbec893"
    :blockers (bz-bugs "745279")
    
    (doto (-> {:name "deleteme"
               :url "http://my.fake/url" 
               :product @test-product}
              katello/newRepository
              uniqueify)
      (ui/create)
      (ui/delete)))
  
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
                  product  (katello/newProduct  {:name "prod"
                                                 :provider provider})]
      (ui/create-all (list org provider))
      (provider/create-discovered-repos-within-product product
                                                       "http://inecas.fedorapeople.org/fakerepos/" ["/brew-repo/" "/cds/content/nature/1.0/i386/rpms/"] {:new-prod true})))
  
  
  (deftest "Repository Autodiscovery for existing product"
    :uuid "cd24049d-9b47-9654-0f03-1c967d240d55"
    :description "Uses the repo autodiscovery tool to create custom repositories within an existing custom product."
    (with-unique [org      (katello/newOrganization {:name "org"})
                  provider (katello/newProvider {:name "prov"
                                                 :org org})
                  product  (katello/newProduct  {:name "prod"
                                                 :provider provider})]
      (ui/create-all (list org provider product))
      (provider/create-discovered-repos-within-product product
                                                       "http://inecas.fedorapeople.org/fakerepos/" ["/brew-repo/" "/cds/content/nature/1.0/i386/rpms/"])))
  
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
                                                 :gpg-key (:name gpgkey)})]
      (let [repo-name "brew-repo"]
        (ui/create-all (list org gpgkey provider product))
        (provider/create-discovered-repos-within-product product
                                                       "http://inecas.fedorapeople.org/fakerepos/" [(format "/%s/" repo-name)])
        (assert/is (repo/gpgkey-associated? product repo-name)))))
  
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
      (let [repo-name "brew-repo"]
        (ui/create-all (list org gpgkey provider product))
        (provider/create-discovered-repos-within-product product
                                                       "http://inecas.fedorapeople.org/fakerepos/" [(format "/%s/" repo-name)])
        (assert/is (not (repo/gpgkey-associated? product repo-name))))))
  
  (deftest "Add the same autodiscovered repo to a product twice"
    :uuid "340ec414-d857-d404-8653-58ee90756828"
    :description "Adds the repositories to the selected product twice."
    (with-unique [org (katello/newOrganization  {:name "org"})
                  provider (katello/newProvider {:name "prov"
                                                 :org org})
                  product (katello/newProduct   {:name "prod"
                                                 :provider provider})]
      (ui/create-all (list org provider product))
      (val/expecting-error-2nd-try (katello.ui-common/errtype :katello.notifications/label-taken-error)
                                   (provider/create-discovered-repos-within-product product
                                                                                    "http://inecas.fedorapeople.org/fakerepos/"  ["/brew-repo/" "/cds/content/nature/1.0/i386/rpms/"])))))
