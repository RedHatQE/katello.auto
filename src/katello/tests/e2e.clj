(ns katello.tests.e2e
  (:refer-clojure :exclude [fn])
  (:require [katello :as kt]
            (katello [client :as client]
                     [rest :as rest]
                     [providers :as provider]
                     repositories
                     environments
                     [systems :as system]
                     [changesets :refer [sync-and-promote]]                    
                     [tasks :refer :all]
                     [conf :refer [*session-user* *session-org* *environments*
                                   config no-clients-defined]])
            [katello.client.provision :as provision]
            [katello.tests.useful :refer [fresh-repo create-recursive]]
            (test.tree [script :refer :all]
                       [builder :refer :all])
            [serializable.fn :refer [fn]]
            [bugzilla.checker :refer [open-bz-bugs]]
            [test.assert :as assert]
            [slingshot.slingshot :refer :all]))

;; Functions

(defn test-client-access
  "Promotes products into target-env. Then on a client machine,
   registers the client to the Katello server, subscribes to the
   products, and then installs packages-to-install."
  [target-env products packages-to-install]
  (let [all-packages (apply str (interpose " " packages-to-install))
        pool-provides-product (fn [{:keys [name] :as product}
                                   {:keys [productName providedProducts] :as pool}]
                                (or (= productName name)
                                    (some #(= (:productName %) name)
                                          providedProducts)))]
    ;;client side
    (provision/with-client "e2e-custom" ssh-conn
      (client/setup-client ssh-conn)
      (client/run-cmd ssh-conn (format "rpm -e %s" all-packages))
      (client/register ssh-conn {:username (:name *session-user*)
                                 :password (:password *session-user*)
                                 :org (-> target-env kt/org :name)
                                 :env (:name target-env)
                                 :force true})
      
      (doseq [product products]
        (if-let [matching-pool (->> ssh-conn
                                    client/my-hostname
                                    (hash-map :name)
                                    kt/newSystem
                                    system/api-pools
                                    (filter (partial pool-provides-product product))
                                    first
                                    :id)]
          (client/subscribe ssh-conn matching-pool)
          (throw+ {:type :no-matching-pool :product product})))
      (let [cmd-results [(client/run-cmd ssh-conn "yum repolist")
                         (client/run-cmd ssh-conn (format "yum install -y --nogpg %s" all-packages))
                         (client/run-cmd ssh-conn (format "rpm -q %s" all-packages))]]
        (->> cmd-results (map :exit-code) (every? zero?) assert/is)))))

;; Tests

(defgroup end-to-end-tests 

  (deftest "Clients can access custom content"
    :blockers (union (blocking-tests "simple sync" "promote content")
                     (open-bz-bugs "784853" "790246")
                     no-clients-defined)
    (let [repo (fresh-repo *session-org*
                           "http://inecas.fedorapeople.org/fakerepos/cds/content/safari/1.0/x86_64/rpms/")
          target-env (first *environments*)
          package-to-install "cheetah"]
      (create-recursive repo)
      (when (rest/is-katello?)
        (sync-and-promote (list repo) target-env))
      (test-client-access target-env (-> repo kt/product list) [package-to-install]))))
