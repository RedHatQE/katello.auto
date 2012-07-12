(ns katello.tests.search
  (:use test.tree.script
        katello.tasks
        katello.ui-tasks
        [tools.verify :only [verify-that]]
        [katello.tests.organizations :only [create-test-org]]
        [katello.tests.users :only [generic-user-details]]
        [bugzilla.checker :only [open-bz-bugs]]
        slingshot.slingshot)
  (:require (katello [api-tasks :as api])))

;; Functions


(defn search-results-valid?
  "Returns a predicate that will be true when the search results both
   contain expected-items and each result item matches the provided
   predicate."
  [pred expected-items]
  (fn [results]
    (boolean (and (every? pred results)
                  (every? (into #{} results) expected-items)))))

(defn verify-simple-search [entity-type create-fn query-string]
  (let [entity-name (uniqueify (str query-string "-forsearch"))
        valid-search-results (search-results-valid?
                              (fn [item] (.contains item query-string)) [entity-name])]
    (create-fn entity-name)
    (search entity-type {:criteria (str query-string "*")})
    (verify-that (valid-search-results (extract-left-pane-list)))))

;; Tests

(defgroup search-tests
  
  (deftest "Search for an organization"
    :description "Search for organizations based on criteria." 
    :blockers    (open-bz-bugs "750120")
      
    (verify-simple-search :organizations #(create-organization %) "myfoobar"))

  (deftest "Search for a user"
    (verify-simple-search :users #(create-user % generic-user-details) "mybazquux"))

  (deftest "Search System Facts"
    (with-unique [system-name "mysystem"
                  system-groups-name "fed"]
      (api/with-admin
        (api/ensure-env-exist "dev" {:prior "Library"}))
        (create-system system-name {:sockets "1"
                                    :system-arch "x86_64"})
        (create-system-groups system-groups-name {:description "rh system-group"})
        (add-system-system-groups system-name system-groups-name))))