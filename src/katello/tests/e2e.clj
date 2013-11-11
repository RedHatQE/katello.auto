(ns katello.tests.e2e
  (:refer-clojure :exclude [fn])
  (:require [katello :as kt]
            (katello [client :as client]
                     [rest :as rest]
                     [ui :as ui]
                     [providers :as provider]
                     repositories
                     environments
                     [blockers :refer [bz-bugs]]
                     [systems :as system]
                     [sync-management :as sync]
                     [changesets :as changesets]
                     [content-view-definitions :as views]
                     [tasks :refer :all]
                     [conf :refer [*session-user* *session-org* 
                                   config no-clients-defined]])
            [katello.client.provision :as provision]
            [katello.tests.useful :refer [fresh-repo create-recursive]]
            (test.tree [script :refer :all]
                       [builder :refer :all])
            [serializable.fn :refer [fn]]
            [test.assert :as assert]
            [slingshot.slingshot :refer :all]))

;; Functions

(defn test-client-access
  "Promotes products into target-env. Then on a client machine,
   registers the client to the Katello server, subscribes to the
   products, and then installs packages-to-install."
  [target-env products packages-to-install]
  (let [all-packages      (apply str (interpose " " packages-to-install))
        katello-details   {:username (:name *session-user*)
                           :password (:password *session-user*)
                           :org (-> target-env kt/org :name)
                           :env (:name target-env)
                           :force true}]
    ;;client side
    (provision/with-queued-client 
      ssh-conn 
      (client/run-cmd ssh-conn (format "rpm -e %s" all-packages))
      (client/register ssh-conn (if (rest/is-katello?)
                                    katello-details
                                    (dissoc katello-details :env)))
      
      (doseq [product products]
        (if-let [matching-pool (system/pool-id (if (rest/is-katello?)
                                                   (->> ssh-conn client/my-hostname
                                                   (hash-map :env target-env, :name)
                                                    kt/newSystem)
                                                   (->> ssh-conn client/my-hostname
                                                   (hash-map :name)
                                                    kt/newSystem)) product)]
          (client/subscribe ssh-conn matching-pool)
          (throw+ {:type :no-matching-pool :product product})))
      (let [cmd-results [(client/run-cmd ssh-conn "yum repolist")
                         (client/run-cmd ssh-conn (format "yum install -y --nogpg %s" all-packages))
                         (client/run-cmd ssh-conn (format "rpm -q %s" all-packages))]]
        (->> cmd-results (every? client/ok?) assert/is)))))

;; Tests

(defgroup end-to-end-tests 

  (deftest "Clients can access custom content"
    :uuid "34fdfac4-7c7c-0c94-4173-c60711d2da24"
    :blockers (conj (bz-bugs "784853" "790246" "959211" "970570")
                    (blocking-tests "simple sync" "promote content"))
    (let [repo               (fresh-repo *session-org*
                              "http://inecas.fedorapeople.org/fakerepos/cds/content/safari/1.0/x86_64/rpms/")
          target-env         (-> {:name "e2e" :org *session-org*} kt/newEnvironment uniqueify)
          package-to-install "cheetah"
          cv                 (-> {:name "content-view" :org *session-org* :published-name "publish-name"}
                                 kt/newContentViewDefinition uniqueify)
          cs                 (-> {:name "cs" :env target-env :content (list cv)}
                                 kt/newChangeset uniqueify)]
      (when (rest/is-katello?)
        (ui/create target-env)
        (ui/create (katello/product repo))
        (ui/create repo)
        (sync/perform-sync (list repo))
        (sync/verify-all-repos-synced (list repo))
        (ui/create cv)
        (ui/update cv assoc :products (list (kt/product repo)))
        (views/publish {:content-defn cv
                        :published-name (:published-name cv)
                        :description "test pub"
                        :org *session-org*})
        (changesets/promote-delete-content cs))       
      (test-client-access target-env (-> repo kt/product list) [package-to-install]))))
