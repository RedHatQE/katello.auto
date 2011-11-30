(ns katello.tests.organizations
  (:refer-clojure :exclude [fn])
  (:require (katello [tasks :as tasks]
                     [api-tasks :as api]
                     [validation :as validate]))
  (:use [com.redhat.qe.verify :only [verify-that]]
        [test.tree.builder :only [data-driven]]
        [serializable.fn :only [fn]]
        [katello.validation :only [duplicate-disallowed field-validation name-field-required expect-error]]))

(def search-org
  "Search for organizations based on criteria."
  (fn [] 
    (api/with-admin-creds
      (let [test-org-name (tasks/uniqueify "searchorg")]
      (api/create-organization test-org-name
       {:description "organization used to test environments."})
      (tasks/validate-search :orgs {:criteria "search"})))))

(def create
  (fn [] (tasks/verify-success
         #(tasks/create-organization
           (tasks/uniqueify "auto-org") "org description"))))

(def delete
  (fn [] (let [org-name (tasks/uniqueify "auto-del")]
          (tasks/create-organization org-name {:description "org to delete immediately"})
          (tasks/delete-organization org-name)
          (let [remaining-org-names (doall (map :name
                                                (api/with-admin-creds
                                                  (api/all-entities :organization))))]
            (verify-that (not (some #{org-name} remaining-org-names)))))))

(def dupe-disallowed
  (fn [] (let [org-name (tasks/uniqueify "test-dup")]
          (duplicate-disallowed tasks/create-organization
                                [org-name {:description "org-description"}]))))

(def name-required
  (fn [] (name-field-required
         tasks/create-organization ["" {:description "org description"}])))

(def valid-name
  (fn [name expected-error]
    (field-validation tasks/create-organization
                      [name {:description "org description"}]
                      (expect-error expected-error))))

(def edit
  (fn [] (let [org-name (tasks/uniqueify "auto-edit")]
          (tasks/create-organization org-name {:description "org to edit immediately"})
          (tasks/edit-organization org-name :description "edited description"))))

(def valid-name-data
  (let [make-data #(list %2 %1)] (concat 
    (validate/variations :name-must-not-contain-characters make-data validate/invalid-character)
    (validate/variations :name-no-leading-trailing-whitespace make-data validate/trailing-whitespace))))
