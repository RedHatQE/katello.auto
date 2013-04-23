(ns katello.tests.providers.custom
  (:require katello
            (katello [ui :as ui]
                     [providers :as provider]
                     [repositories :as repo]
                     [tasks :refer :all]
                     [validation :as val]
                     [organizations :as organization]
                     [conf :as conf])
            [test.tree.script :refer [defgroup deftest]]
            [bugzilla.checker :refer [open-bz-bugs]]
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
  :blockers (open-bz-bugs "751910")
  
  (deftest "Create a custom product"
    (ui/create (reset! test-product
                       (uniqueify (katello/newProduct {:provider @test-provider
                                                       :name "prod"
                                                       :description "test product"})))))

    
  (deftest "Delete a custom product"
    :blockers (open-bz-bugs "729364")
    
    (doto (uniqueify (katello/newProduct {:provider @test-provider
                                          :name "deleteme"
                                          :description "test product to delete"}))
      (ui/create)
      (ui/delete)))
  
  (deftest "Create a repository"
    :blockers (open-bz-bugs "729364")
    
    (-> {:name "repo"
         :url "http://test.com/myurl"
         :product @test-product}
        katello/newRepository
        uniqueify
        ui/create))
  
  (deftest "Delete a repository"
    :blockers (open-bz-bugs "745279")
    
    (doto (-> {:name "deleteme"
               :url "http://my.fake/url" 
               :product @test-product}
              katello/newRepository
              uniqueify)
      (ui/create)
      (ui/delete)))
  
  (deftest "Create two products with the same name, in different orgs"
    :blockers (open-bz-bugs "784712" "802795")
    
    (with-unique [provider (katello/newProvider {:name "prov"})
                  product (katello/newProduct {:name "prod"
                                               :provider provider})]
      (doseq [org (->> {:name "prov-org"}
                       katello/newOrganization
                       uniques
                       (take 2))]
        (create-recursive (update-in product [:provider :org] (constantly org))))))
  
  
  (deftest "Create two products with same name in same org different provider"
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
    :blockers (open-bz-bugs "927335")
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
