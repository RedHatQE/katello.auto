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


  (deftest "Perform search operation on an organization"
    :data-driven true
    :description "Search for organizations based on criteria." 
 
    (fn [org searchterms]
      (let [[name opts] org
            unique-org [(uniqueify name) opts]]
        (apply create-organization unique-org)
        (search :organizations searchterms)
        (let [valid-search-results (search-results-valid?
                                    (constantly true)
                                    [(first unique-org)])]
          (verify-that (valid-search-results (extract-left-pane-list))))
        (if-let [favname (:add-as-favorite searchterms)]
          (search :organizations {:with-favorite favname}))))
    
    [[["test123" {:initial-env-name "test" :description "This is a test123 org"}] {:criteria "test123*"}]
     [["test-123" {:initial-env-name "dev" :description "This is a test-123 org"}] {:criteria "name:test-123*"}]
     [["test" {:initial-env-name "dev" :description "This is a test org"}] {:criteria "description:\"This is a test org\""}]
     [["test" {:initial-env-name "dev" :description "This is a test org"}] {:criteria "description:(+test+org)"}]
     [["test" {:initial-env-name "dev" :description "This is a test org"}] {:criteria "environment:dev*"}]
     [["test" {:initial-env-name "dev" :description "This is a test org"}] {:criteria "name:test*" :add-as-favorite "true"}]])

  
  (deftest "Perform search using strings contatining latin-1 characters"
       :description "Search for organizations names including latin-1 characters in search string." 
        (create-organization "niños"  {:description "This is a test org with latin charcters in name" :initial-env-name "test"})
        (create-organization "bilingüe" {:description "This is a test org with spanish characters like bilingüe" :initial-env-name "test"})
        (create-organization "misión"  {:description "This is a test org with spanish char misión,biños  " :initial-env-name "dev"})
        (create-organization "biños" {:description "This is a test_123 org" :initial-env-name "dev"})
         (search    :organizations        {:criteria "niños"})
         (search    :organizations        {:criteria "bilingüe"})
         (search    :organizations        {:criteria "misión"})
         (search    :organizations        {:criteria "name:?iños"})
         (search    :organizations        {:criteria "description:\"This is a test org with spanish char misión,biños\""})
         (clear-search))

  (deftest "Perform search using strings containing multi-byte characters"
       :description "Search for organizations names including multi-byte characters in search string." 
        (create-organization "test_华语華語"  {:description "This is a test org with multi-byte charcters in name" :initial-env-name "test"})
        (create-organization "兩千三百六十二" {:description "This is a test org with multi-byte characters like తెలుగు" :initial-env-name "test"})
        (create-organization "hill_山"  {:description "This is a test org with multi-byte char like hill_山  兩千三百六十二, test_华语華語" :initial-env-name "dev"})
        (create-organization "Российская" {:description "This is a test_123 org" :initial-env-name "dev"})
        (create-organization "తెలుగు" {:description "This is a test_123 org" :initial-env-name "dev"})
         (search    :organizations        {:criteria "兩千三百六十二"})
         (search    :organizations        {:criteria "test_华语華語"})
         (search    :organizations        {:criteria "తెలుగు"})
         (search    :organizations        {:criteria "name:test_华语華語"})
         (search    :organizations        {:criteria "description:\"This is a test org with multi-byte char like hill_山  兩千三百六十二, test_华语華語\""})
         (clear-search))

  (deftest "'Save as favourite' should save the selected search criteria/pattern"
       :description "Save selected search criteria as favorite."
       (with-unique[username "test"]
         (create-user    username generic-user-details)
          (search :users        {:criteria "test*" :add-as-favorite "true"})
          (clear-search)))
  
  (deftest "Re-save same search criteria as favorite"
       :description "Save existing saved search criteria again as favorite."
       (with-unique[username "test123"]
        (create-user    username generic-user-details)
         (search :users        {:criteria "test123*" :add-as-favorite "true"})
         (search :users        {:criteria "test123*" :add-as-favorite "true"})
         (clear-search)))
     
  (deftest "use the existing saved favorite search pattern"
       :description "using saved search pattern from favorite list"
       (with-unique[username "saved_user"]
        (create-user    username generic-user-details)
         (search :users        {:criteria "saved_user*" :add-as-favorite "true"})
         (search :users       {:with-favorite "saved_user*"})
         (clear-search)))
  
  (deftest "Search for users"
       :description "Search for a user based on default criteria i.e. name" 
       (let [username1 "user1"
                     username2 "user2"
                     pw "password"]
        (create-user username1 {:password pw
                                 :email (str username1"@my.org")})
        (create-user username2 {:password pw
                                 :email (str username2"@my.org")})
         (search    :users        {:criteria "user1"})
         (search    :users        {:criteria "username:user2*"})
         (search    :users        {:criteria "email:\"user1@my.org\""})
         (search    :users        {:criteria "username:\"\" "})
         (search    :users        {:criteria " "})
         (clear-search)))
  
   (deftest "use of lucene syntax with search queries"
      :description "using lucene synataxes in search queries"
      (with-unique [username1 "lucene1"
                     username2 "lucene2"]
        (create-user username1 {:password "password" :email (str username1 "@my.org")})
        (create-user username2 {:password "password" :email (str username2 "@my.org")})
        (create-user "lucene3" {:password "password" :email "lucene3@my.org"})
         (search    :users        {:criteria "username:lucene*"})
         (search    :users        {:criteria "lucene?"})
         (search    :users        {:criteria "email:\"*@my.org\""})
         (search    :users        {:criteria "email:@my.org"})
         (search    :users        {:criteria "email:my.org"})
         (clear-search)))
   
   (deftest "search for roles"
        (let [rolename1 (uniqueify "manager")
        rolename2 (uniqueify "power_user")]
         (create-role rolename1 {:description "This role is to manage an org"})
         (create-role rolename2 {:description "This role has some admin permissions"})
        (search    :roles        {:criteria "manager*"})
        (search :roles {:criteria "name:power_user*"})
        (search :roles {:criteria "description:\"This role is to manage an org\""})
        (search :roles {:criteria "description:(+manage+org)"})
        (clear-search)))
   
  (deftest "ROLES:'Save as favourite' should save the selected search criteria/pattern"
       :description "ROLES:Save selected search criteria as favorite."
       (create-role (uniqueify "test") {:description "This role is to test save favorite function"})
       (search :roles        {:criteria "test*" :add-as-favorite "true"})
       (clear-search))
  
  (deftest "ROLES: Re-save same search criteria as favorite"
       :description "Save existing saved search criteria again as favorite."
     (create-role "role1" {:description "This role is to test save favorite function"})
     (create-role "role2" {:description "This role is to test save favorite function"})
       (search :roles        {:criteria "name:role?" :add-as-favorite "true"})
       (search :roles        {:criteria "name:role?" :add-as-favorite "true"})
       (clear-search))
     
  (deftest "ROLES: use the existing saved favorite search pattern"
       :description "using saved search pattern from favorite list"
      (create-role (uniqueify "saved_role"){:description "This role is to test save favorite function"})
       (search :roles        {:criteria "name:saved*" :add-as-favorite "true"})
       (search :roles       {:with-favorite "name:saved*"})
       (clear-search))
  
  (deftest "search providers"
        :description "search providers by defualt criteria  i.e. name"
        (let [provider-name (uniqueify "myprovider")
            product-name (uniqueify "testproduct")
            repo-name (uniqueify "testrepo")]
        (create-provider {:name provider-name
                          :description "my test provider"})
         (add-product {:provider-name provider-name
                       :name product-name})
         (add-repo {:provider-name provider-name
                    :product-name product-name
                    :name repo-name
                    :url "http://inecas.fedorapeople.org/fakerepos/cds/content/safari/1.0/x86_64/rpms/"})
          (search    :content       {:criteria "myprovider*"})
          (search    :content        {:criteria "description:\"my test provider\""})
          (search    :content        {:criteria "repo:testproduct*"})
          (search    :content        {:criteria "product:testproduct*"})
          (search    :content        {:criteria "description:\"my test provider\"" :add-as-favorite "true"})
          (search    :content        {:criteria "repo:test*" :add-as-favorite "true"})
          (search    :content        {:with-favorite "repo:test*"})
          (clear-search)))
     
     (deftest "search activation keys"
         :description "search activation keys by default criteria i.e. name"
         (create-activation-key {:name (uniqueify "auto-key")
                            :description "my auto-key"
                            :environment "dev"})
          (create-activation-key {:name (uniqueify "activation-key")
                            :description "my activation-key"
                            :environment "dev"})
            (search   :activation-keys        {:criteria "auto-key*"})
            (search   :activation-keys        {:criteria "name:activation-key*"})
            (search   :activation-keys        {:criteria "description:\"my activation-key\""})
            (search   :activation-keys        {:criteria "environment:dev*"})
            (search   :activation-keys        {:criteria "*-key*" :add-as-favorite "true"})
            (search   :activation-keys        {:criteria "environment:dev*" :add-as-favorite "true"})
            (search   :activation-keys        {:with-favorite "environment:dev*"})
            (clear-search))
     
     (deftest "search sync_plan"
         :description "search sync plans by default criteria i.e. name"
         (create-sync-plan {:name (uniqueify "my_plan")
                       :description "my sync plan"
                       :interval "hourly"
                       :start-date (java.util.Date.)})
            (search   :sync-plans        {:criteria "my_plan"})
            (search   :sync-plans        {:criteria "interval:hourly"})
            (search   :sync-plans        {:criteria "description:\"my sync plan\""})
            (search   :sync-plans        {:criteria "sync_date:2012-07-07"})
            (search   :sync-plans        {:criteria "name:my_*" :add-as-favorite "true"})
            (search   :sync-plans        {:criteria "interval:hourly" :add-as-favorite "true"})
            (search   :sync-plans        {:with-favorite "interval:hourly"})
            (clear-search))
     
(deftest "Search on changeset-history"
       :description "End to End promotion and then performing search with promotion changeset history"
           (let [provider-name (uniqueify "ami")
            product-name "safari-1_0"
            repo-name (uniqueify "safari-x86_64")
            org-name (uniqueify "test_org")
            temp-name (uniqueify "test_template")]
      (create-organization org-name {:initial-env-name "test"})
      (create-environment "dev" {:org-name org-name :prior-env "test"})
      (switch-org org-name)
      (create-provider {:name provider-name :description "my ami"})
      (add-product {:provider-name provider-name
                    :name product-name})
      (add-repo {:provider-name provider-name
                 :product-name product-name
                 :name repo-name
                 :url "http://inecas.fedorapeople.org/fakerepos/cds/content/safari/1.0/x86_64/rpms/"})
       (sync-repos [repo-name] 120000)
       #_(create-changeset "Library" "test" "newset")
       #_(add-to-changeset "newset" "Library" "test" {:product ["safari-1_0"]})
       (promote-content "Library" "dev" {:products [product-name]})
       (create-template {:name temp-name :description "template to perform search operations"})
       (add-to-template temp-name [{:product product-name
                                   :repositories [repo-name]}])
       (promote-content "Library" "dev" {:templates [temp-name]})
       (search :changeset-promotion-history {:criteria "changeset*"})
       (clear-search)
       (search :changeset-promotion-history {:criteria "product:safari-1_0"})
       (search :changeset-promotion-history {:criteria "user:admin"})
       (search :changeset-promotion-history {:criteria "system_template:\"test_template*\""})
       (search :changeset-promotion-history {:criteria "system_template:\"test_template*\"" :add-as-favorite "true"})
       (search :changeset-promotion-history {:criteria "product:safari-1_0" :add-as-favorite "true"})
       (search :changeset-promotion-history {:with-favorite "product:safari-1_0"})
       (clear-search))))