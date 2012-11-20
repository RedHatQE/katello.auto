(ns katello.tests.e2e
  (:refer-clojure :exclude [fn])
  (:require (katello [client :as client]
                     [api-tasks :as api]
                     [providers :as provider]
                     [changesets :refer [sync-and-promote]]
                     [organizations :as organization]
                     [tasks :refer :all]
                     [ui-tasks :refer :all]
                     [conf :refer [*session-user* *session-password*
                                   *environments* config no-clients-defined
                                   with-org]])
            [katello.client.provision :as provision]
            (test.tree [script :refer :all]
                       [builder :refer :all])
            [serializable.fn :refer [fn]]
            [bugzilla.checker :refer [open-bz-bugs]]
            [tools.verify :refer [verify-that]]
            [slingshot.slingshot :refer :all]))

;; Functions

(defn test-client-access
  "In an org named org-name, promotes products into target-env. Then
   on a client machine, registers the client to the Katello server,
   subscribes to the products, and then installs packages-to-install.
   Example of products: [ {:name 'myprod' :productName 'myprod
   24/7' :repos ['myrepoa' 'myrepob']} ]"
  [org-name target-env products packages-to-install]
  (let [all-products (map #(or (:productName %1) (:name %1)) products)
        all-packages (apply str (interpose " " packages-to-install))
        pool-provides-product (fn [prod pool]
                                (or (= (:productName pool) prod)
                                    (some #(= (:productName %) prod)
                                          (:providedProducts pool))))]
    
    (when (api/is-katello?)
      (with-org org-name
        (organization/switch)
        (sync-and-promote products library target-env)))

    ;;client side
    (provision/with-client "e2e-custom" ssh-conn
      (client/setup-client ssh-conn)
      (client/run-cmd ssh-conn (format "rpm -e %s" all-packages))
      (client/register ssh-conn {:username *session-user*
                                 :password *session-password*
                                 :org org-name
                                 :env target-env
                                 :force true})
     
      (doseq [product-name all-products]
        (if-let [matching-pool (->> (api/system-available-pools (client/my-hostname ssh-conn))
                                  (filter (partial pool-provides-product product-name))
                                  first
                                  :id)]
          (client/subscribe ssh-conn  matching-pool)
          (throw+ {:type :no-matching-pool :product-name product-name})))
      (let [cmd-results [(client/run-cmd ssh-conn "yum repolist")
                         (client/run-cmd ssh-conn (format "yum install -y --nogpg %s" all-packages))
                         (client/run-cmd ssh-conn (format "rpm -q %s" all-packages))]]
        (->> cmd-results (map :exit-code) (every? zero?) verify-that)))))

;; Tests

(defgroup end-to-end-tests 

  (deftest "Clients can access custom content"
   ;; :blockers (union (blocking-tests "simple sync" "promote content")
   ;;                  (open-bz-bugs "784853" "790246")
   ;;                  no-clients-defined)
   
    (let [provider-name (uniqueify "fedorapeople")
          product-name (uniqueify "safari-1_0")
          repo-name (uniqueify "safari-x86_64")
          target-env (first *environments*)
          cs-name (uniqueify "promo-safari")
          package-to-install "cheetah"]
      (organization/switch)
      (api/ensure-env-exist target-env {:prior library})
      (provider/create {:name provider-name})
      (provider/add-product {:provider-name provider-name
                             :name product-name})
      (provider/add-repo {:provider-name provider-name
                          :product-name product-name
                          :name repo-name
                          :url "http://inecas.fedorapeople.org/fakerepos/cds/content/safari/1.0/x86_64/rpms/"} )
      (let [products [{:name product-name :repos [repo-name]}]]
        (when (api/is-katello?)
          (sync-and-promote products library target-env))
        (test-client-access (@config :admin-org)
                            target-env
                            products
                            [package-to-install] )))))
