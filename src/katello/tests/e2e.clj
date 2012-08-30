(ns katello.tests.e2e
  (:refer-clojure :exclude [fn])
  (:require (katello [client :as client]
                     [api-tasks :as api]
                     [providers :as provider]
                     [changesets :refer [sync-and-promote]]
                     [organizations :as organization]
                     [tasks :refer :all]
                     [ui-tasks :refer :all]
                     [conf :refer [*session-user* *session-password* *environments* config no-clients-defined]]) 
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
   Example of products: [ {:name 'myprod' :poolName 'myprod
   24/7' :repos ['myrepoa' 'myrepob']} ]"
  [org-name target-env products packages-to-install]
  (let [all-pools (map #(or (:poolName %1)
                            (:name %1)) products)
        all-packages (apply str (interpose " " packages-to-install))]
    
    (when (api/is-katello?)
      (sync-and-promote products library target-env))

    ;;client side
    (client/setup-client)
    (client/run-cmd (format "rpm -e %s" all-packages))
    (client/register {:username *session-user*
                      :password *session-password*
                      :org org-name
                      :env target-env
                      :force true})
                      
    (doseq [pool-name all-pools]
      (if-let [matching-pool (->> (api/system-available-pools (-> client/*runner* .getConnection .getHostname))
                                (filter #(= (:poolName %) pool-name))
                                first
                                :poolId)]
        (client/subscribe matching-pool)
        (throw+ {:type :no-matching-pool :pool-name pool-name})))
    (let [cmd-results [(client/run-cmd "yum repolist")
                       (client/run-cmd (format "yum install -y --nogpg %s" all-packages))
                       (client/run-cmd (format "rpm -q %s" all-packages))]]
      (->> cmd-results (map :exit-code) (every? zero?) verify-that))))

;; Tests

(defgroup end-to-end-tests 

  (deftest "Clients can access custom content"
    :blockers (union (blocking-tests "simple sync" "promote content")
                     (open-bz-bugs "784853" "790246")
                     no-clients-defined)
   
    (let [provider-name (uniqueify "fedorapeople")
          product-name (uniqueify "safari-1_0")
          repo-name (uniqueify "safari-x86_64")
          target-env (first *environments*)
          cs-name (uniqueify "promo-safari")
          package-to-install "cheetah"]
      (organization/execute-with (@config :admin-org)
        (api/with-admin
          (api/with-admin-org
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
                                  [package-to-install] ))))))))
