(ns katello.tests.e2e
  (:require (katello [client :as client]
                     [api-tasks :as api]))
  (:refer-clojure :exclude [fn])
  (:use [serializable.fn :only [fn]]
        [katello.tasks]
        [com.redhat.qe.verify :only [verify-that]]
        [katello.conf :only [*session-user* *session-password* config]]
        [slingshot.slingshot :only [try+]]))



(def client-access-custom
  (fn []
    (let [provider-name (uniqueify "fedorapeople")
          product-name (uniqueify "aeolus")
          repo-name (uniqueify "aeolus-x86_64")
          target-env (@config :first-env)
          cs-name (uniqueify "promo-aeolus")
          package-to-install "python-httplib2"]
      (api/with-creds *session-user* *session-password*
        (api/with-admin-org
          (api/ensure-env-exist target-env {:prior locker})
          (create-provider {:name provider-name})
          (add-product {:provider-name provider-name
                        :name product-name})
          (add-repo {:provider-name provider-name
                     :product-name product-name
                     :name repo-name
                     :url "http://repos.fedorapeople.org/repos/aeolus/conductor/latest-release/6Server/x86_64/"} )
          (let [sync-results (sync-repos [repo-name] {:timeout 600000})]
            (verify-that (every? (fn [[_ res]] (sync-success? res))
                                 sync-results)))
          (promote-content locker target-env {:products [product-name]})

          ;;client side
          (client/setup-client)
          (client/run-cmd (format "rpm -e %s" package-to-install))
          (client/register {:username *session-user*
                            :password *session-password*
                            :org (@config :admin-org)
                            :env (@config :first-env)
                            :force true})
          (client/subscribe (->> (api/system-available-pools (-> client/*runner* .getConnection .getHostname))
                               (filter #(= (:poolName %) product-name))
                               first))
          (let [repolist-return-code (client/run-cmd "yum repolist")
                install-return-code (client/run-cmd (format "yum install -y --nogpg %s" package-to-install))]
            (verify-that (every? #(= 0 %) [repolist-return-code install-return-code]))))))))







