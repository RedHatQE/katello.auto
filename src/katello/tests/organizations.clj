(ns katello.tests.organizations
  (:refer-clojure :exclude [fn])
  (:require (katello [tasks :as tasks]
                     [api-tasks :as api]
                     [validation :as validate]))
  (:use [com.redhat.qe.verify :only [verify-that]]
        [test.tree :only [fn data-driven]]))

(def create (fn [] (tasks/verify-success
                   #(tasks/create-organization
                     (tasks/uniqueify "auto-org") "org description"))))

(def delete (fn [] (let [org-name (tasks/uniqueify "auto-del")]
                    (tasks/create-organization org-name "org to delete immediately")
                    (tasks/delete-organization org-name)
                    (let [remaining-org-names (doall (map :name (api/all-entities :organization)))]
                      (verify-that (not (some #{org-name} remaining-org-names)))))))

(def dupe-disallowed (fn [] (let [org-name (tasks/uniqueify "test-dup")]
                             (validate/duplicate-disallowed
                              #(tasks/create-organization org-name "org-description")))))

(def name-required (fn [] (validate/name-field-required
                          #(tasks/create-organization nil "org description"))))

(def valid-name (fn [name expected-error]
                  (validate/field-validation
                   #(tasks/create-organization name "org description")
                   expected-error)))

(def edit (fn [] (let [org-name (tasks/uniqueify "auto-edit")]
                  (tasks/create-organization org-name "org to edit immediately")
                  (tasks/edit-organization org-name :description "edited description"))))

(def valid-name-data (concat 
                      (validate/variations [:invalid-character
                                            :name-must-not-contain-characters])
                      (validate/variations [:trailing-whitespace
                                            :name-no-leading-trailing-whitespace])))
