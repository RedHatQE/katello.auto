(in-ns 'katello.tests.providers)

;; Variables

(def test-provider-name (atom nil))
(def test-product-name (atom nil))


;; Functions

(defn create-test-provider
  "Sets up a test custom provider to be used by other tests."
  []
  (provider/create {:name (reset! test-provider-name (uniqueify "cust"))
           :description "my description"}))

(defn create-same-product-in-multiple-providers
  [product-name providers]
  (doseq [provider providers]
    (provider/add-product {:provider-name provider
                  :name product-name})))

(defn create-same-product-name-in-multiple-orgs
  [product-name orgs]
  (doseq [org orgs]
    (organization/switch org)
    (let [provider-name (uniqueify "prov")]
      (api/with-admin
        (api/with-org org
          (api/create-provider provider-name))
        (provider/add-product {:provider-name provider-name
                                :name product-name})))))

;; Tests

(defgroup custom-product-tests
  :group-setup create-test-provider
  :blockers (open-bz-bugs "751910")

  (deftest "Create a custom product"
    (provider/add-product {:provider-name @test-provider-name
                            :name (reset! test-product-name (uniqueify "prod"))
                            :description "test product"})

    
    (deftest "Delete a custom product"
      :blockers (open-bz-bugs "729364")
      
      (let [product {:provider-name @test-provider-name
                     :name (uniqueify "deleteme")
                     :description "test product to delete"}]
        (provider/add-product product)
        (provider/delete-product product)))

    (deftest "Create a repository"
      :blockers (open-bz-bugs "729364")

      (provider/add-repo {:provider-name @test-provider-name
                           :product-name @test-product-name
                           :name (uniqueify "repo")
                           :url "http://test.com/myurl"})

      
      (deftest "Delete a repository"
        :blockers (open-bz-bugs "745279")
        
        (let [repo {:provider-name @test-provider-name
                    :product-name @test-product-name
                    :name (uniqueify "deleteme")
                    :url "http://my.fake/url"}]
          (provider/add-repo repo)
          (provider/delete-repo repo))))

    
    (deftest "Create two products with the same name, in different orgs"
      :blockers (open-bz-bugs "784712" "802795")

      (with-n-new-orgs 2 create-same-product-name-in-multiple-orgs))


    (deftest "Create two products with same name in same org different provider"
      :description "Creates products with the same name in different
                    providers, where the providers are in the same
                    org."
      (with-two-providers create-same-product-in-multiple-providers))))
