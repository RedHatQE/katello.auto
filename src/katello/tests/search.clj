(ns katello.tests.search
  (:use test.tree.script
        katello.tasks
        katello.ui-tasks
        [tools.verify :only [verify-that]]
        [katello.tests.organizations :only [create-test-org]]
        [katello.tests.users :only [generic-user-details]]
        [bugzilla.checker :only [open-bz-bugs]]
        slingshot.slingshot))

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
    (create-environment "dev3k" {:org-name "ACME_Corporation"
                                 :description "simple dev env"
                                 :prior-env "Library"})
    (create-system "dhcp201-101.englab.pnq.redhat.com" {:sockets "1"})
    (create-system-groups "bid7" {:description "kar system-group"})
    (add-system-system-groups "dhcp201-101.englab.pnq.redhat.com" "ked")))
