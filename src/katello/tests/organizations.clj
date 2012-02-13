(ns katello.tests.organizations
  (:refer-clojure :exclude [fn])
  (:require (katello [api-tasks :as api]
                     [validation :as validate]))
  (:use katello.tasks
        [katello.conf :only [config]]
        [com.redhat.qe.verify :only [verify-that]]
        [test.tree.builder :only [data-driven]]
        [serializable.fn :only [fn]]
        [katello.validation :only [duplicate-disallowed field-validation name-field-required expect-error]]))

(def search-org
  "Search for organizations based on criteria."
  (fn [] 
    (api/with-admin-creds
      (let [test-org-name (uniqueify "searchorg")]
        (api/create-organization test-org-name
                                 {:description "organization used to test environments."})
        (validate-search :orgs {:criteria "search"})))))

(def create
  (fn [] (verify-success
         #(create-organization
           (uniqueify "auto-org") "org description"))))

(def delete
  (fn [] (let [org-name (uniqueify "auto-del")]
          (create-organization org-name {:description "org to delete immediately"})
          (delete-organization org-name {:confirm-timeout-ms 200000})
          (let [remaining-org-names (doall (map :name
                                                (api/with-admin-creds
                                                  (api/all-entities :organization))))]
            (verify-that (not (some #{org-name} remaining-org-names)))))))

(def dupe-disallowed
  (fn [] (let [org-name (uniqueify "test-dup")]
          (duplicate-disallowed create-organization
                                [org-name {:description "org-description"}]))))

(def name-required
  (fn [] (name-field-required
         create-organization ["" {:description "org description"}])))

(def valid-name
  (fn [name expected-error]
    (field-validation create-organization
                      [name {:description "org description"}]
                      (expect-error expected-error))))

(def edit
  (fn [] (let [org-name (uniqueify "auto-edit")]
          (create-organization org-name {:description "org to edit immediately"})
          (edit-organization org-name :description "edited description"))))

(def valid-name-data
  (let [make-data #(list %2 %1)]
    (concat 
     (validate/variations :name-must-not-contain-characters make-data validate/invalid-character)
     (validate/variations :name-no-leading-trailing-whitespace make-data validate/trailing-whitespace))))

(def recreate-org-with-same-data
  (fn []
    (let [org-name (uniqueify "delorg")
          provider-name (uniqueify "delprov")
          product-name (uniqueify "delprod")
          repo-name (uniqueify "delrepo")
          repo-url "http://blah.com/blah"
          createfn (fn []
                     (create-organization org-name {:description "org to delete and recreate"})
                     (switch-org org-name)
                     (create-provider {:name provider-name
                                       :description "provider to del and recreate"})
                     (add-product {:provider-name provider-name
                                   :name product-name})
                     (add-repo {:name repo-name
                                :provider-name provider-name
                                :product-name product-name
                                :url repo-url}))]
      (try (createfn)
           (switch-org (@config :admin-org))
           (delete-organization org-name {:confirm-timeout-ms 200000})
           ;;wait for delayed job to delete org
           (Thread/sleep 30000)
           (createfn)
           (finally (switch-org (@config :admin-org)))))))