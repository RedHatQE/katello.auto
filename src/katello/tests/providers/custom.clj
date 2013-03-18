(ns katello.tests.providers.custom
  (:require katello
            (katello [api-tasks :as api]
                     [providers :as provider]
                     [repositories :as repo]
                     [tasks :refer :all]
                     [organizations :as organization]
                     [conf :refer [with-org]])
            [test.tree.script :refer [defgroup deftest]]
            [bugzilla.checker :refer [open-bz-bugs]]
            [katello.tests.providers :refer [with-n-new-orgs with-two-providers]]))

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

(defn create-same-product-in-multiple-providers
  [product-name providers]
  (doseq [provider providers]
    (provider/add-product {:provider-name provider
                           :name product-name})))

(defn create-same-product-name-in-multiple-orgs
  [product-name orgs]
  (doseq [org orgs]
    (with-org org
      (organization/switch)
      (let [provider-name (uniqueify "prov")]
        (api/create-provider provider-name)
        (provider/add-product {:provider-name provider-name
                               :name product-name})))))

(defn with-two-providers
  "Create two providers with unique names, and call f with a unique
  entity name, and the provider names. Used for verifying (for
  instance) that products with the same name can be created in 2
  different providers."
  [f]
  (let [ent-name (uniqueify "samename")
        providers (take 2 (unique-names "ns-provider"))]
    (doseq [provider providers]
      (api/create-provider provider))
    (f ent-name providers)))
;; Tests

(defgroup custom-product-tests
  :group-setup create-test-provider
  :blockers (open-bz-bugs "751910")
  
  (deftest "Create a custom product"
    (ui/create (reset! test-product
                       (uniqueify (katello/newProduct {:provider @test-provider
                                                       :name "prod"
                                                       :description "test product"}))))

    
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
          ui/create)
      
      (deftest "Delete a repository"
        :blockers (open-bz-bugs "745279")

        (doto (-> {:name "deleteme"
                   :url "http://my.fake/url" 
                   :product @test-product}
                  katello/newRepository
                  uniqueify)
          (ui/create)
          (ui/delete))))

    (deftest "Create two products with the same name, in different orgs"
      :blockers (open-bz-bugs "784712" "802795")

      (with-n-new-orgs 2 create-same-product-name-in-multiple-orgs))


    (deftest "Create two products with same name in same org different provider"
      :description "Creates products with the same name in different
                    providers, where the providers are in the same
                    org."
      (with-two-providers create-same-product-in-multiple-providers))))
