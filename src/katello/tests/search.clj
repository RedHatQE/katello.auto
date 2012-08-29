(ns katello.tests.search
  (:use test.tree.script
        katello.tasks
        [katello.organizations :only [create-organization]]
        [katello.users :only [create-user]]
        [katello.sync-management :only [create-sync-plan]]
        katello.ui-tasks
        katello.systems
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
      
    (verify-simple-search :organizations create-organization "myfoobar"))
  

  (deftest "Search for a user"
    (verify-simple-search :users #(create-user % generic-user-details) "mybazquux"))

   (deftest "Perform search operation on systems"
    :data-driven true
    :description "Search for a system based on criteria."
    
    (fn [system searchterms & [system-group]]
      (api/with-admin
        (api/ensure-env-exist "dev" {:prior "Library"}))
      (let [[name opts] system
            unique-system [(uniqueify name) opts]
            [sgname opt] system-group
            unique-sg [(uniqueify sgname) opt]]
        (apply create-system unique-system)
        (if (not (nil? system-group))
          (do
           (edit-system (first unique-system) {:description "most unique system"})
           (apply create-system-group unique-sg)
           (add-to-system-group (first unique-sg) (first unique-system))))
        (search :systems searchterms)
        (let [valid-search-results (search-results-valid?
                                     (constantly true)
                                     [(first unique-system)])]
          (verify-that (valid-search-results (extract-left-pane-list))))))
    [[["mysystem3" {:sockets "4" :system-arch "x86_64"}] {:criteria "description: \"most unique system\""} ["fed" {:description "centos system-group"}]]
     [["mysystem3" {:sockets "2" :system-arch "x86"}] {:criteria "system_group:fed1*"} ["fed1" {:description "rh system-group"}]]
     [["mysystem1" {:sockets "1" :system-arch "x86_64"}] {:criteria "name:mysystem1*"}]
     [["mysystem-123" {:sockets "1" :system-arch "x86"}] {:criteria "network.hostname:mysystem-123*"}]
     [["mysystem2" {:sockets "2" :system-arch "i686"}] {:criteria "mysystem2*"}]])


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
          (verify-that (valid-search-results (extract-left-pane-list))))))
    [[["test123" {:initial-env-name "test" :description "This is a test123 org"}] {:criteria "test123*"}]
     [["test-123" {:initial-env-name "dev" :description "This is a test-123 org"}] {:criteria "name:test-123*"}]
     [["test" {:initial-env-name "dev" :description "This is a test org"}] {:criteria "description:\"This is a test org\""}]
     [["test" {:initial-env-name "dev" :description "This is a test org"}] {:criteria "description:(+test+org)"}]
     (with-meta
       [["test" {:initial-env-name "dev" :description "This is a test org"}] {:criteria "environment:dev*"}]
       {:blockers (open-bz-bugs "852119")})])
     
 

  (deftest "Search operation with latin-1/multi-byte search strings on an organization"
    :data-driven true
    :description "Search for organizations names including latin-1/multi-byte characters in search string."
    :blockers    (open-bz-bugs "832978")
    (fn [org searchterms]
      (let [[name opts] org
            unique-org [(uniqueify name) opts]]
        (apply create-organization unique-org)
        (search :organizations searchterms)
        (let [valid-search-results (search-results-valid?
                                    (constantly true)
                                    [(first unique-org)])]
          (verify-that (valid-search-results (extract-left-pane-list))))))

    [[["niños"  {:initial-env-name "test" :description "This is a test org with latin charcters in name"}] {:criteria "niños*"}]
     [["bilingüe" {:description "This is a test org with spanish characters like bilingüe" :initial-env-name "test"}]  {:criteria "bilingüe*"}]
     [["misión"  {:description "This is a test org with spanish char misión,biños  " :initial-env-name "dev"}] {:criteria "misión*"}]
     [["biños" {:description "This is a test_123 org" :initial-env-name "dev"}]  {:criteria "name:?iños*"}]
     [["misión"  {:description "This is a test org with spanish char misión,biños " :initial-env-name "dev"}] {:criteria "description:\"This is a test org with spanish char misión,biños\""}]
     [["test_华语華語"  {:description "This is a test org with multi-byte charcters in name" :initial-env-name "test"}] {:criteria "test_华语華語*"}]
     [["兩千三百六十二" {:description "This is a test org with multi-byte characters like తెలుగు" :initial-env-name "test"}] {:criteria "兩千三百六十二*"}]
     [["hill_山"  {:description "This is a test org with multi-byte char like hill_山  兩千三百六十二, test_华语華語" :initial-env-name "dev"}] {:criteria "description:\"This is a test org with multi-byte char like hill_山  兩千三百六十二, test_华语華語\""}]
     [["తెలుగు" {:description "This is a test_123 org" :initial-env-name "dev"}] {:criteria "తెలుగు*"}]])
  
  (deftest "Search for users"
    :data-driven true
    :description "Search for a user based on criteria and with use of lucene-syntax" 
    (fn [user searchterms]
      (let [[name opts] user
            unique-user [(uniqueify name) opts]]
        (apply create-user unique-user)
        (search :users searchterms)
        (let [valid-search-results (search-results-valid?
                                    (constantly true)
                                    [(first unique-user)])]
          (verify-that (valid-search-results (extract-left-pane-list))))))
    
    [[["username1" {:password "password" :email "username1@my.org"}] {:criteria "username1*"}]
     [["username2" {:password "password" :email "username2@my.org"}] {:criteria "username:username?*"}]
     [["lucene4"   {:password "password" :email "lucene4@my.org"}] {:criteria "email:\"*@my.org\""}]
     [["lucene5"   {:password "password" :email "lucene5@my.org"}]  {:criteria "email:@my.org"}]
     [["lucene6"   {:password "password" :email "lucene6@my.org"}]  {:criteria "email:my.org"}]])
  
  (deftest "search activation keys"
    :data-driven true
    :description "search activation keys by default criteria i.e. name"
    (fn [key_opt searchterms]
      (api/with-admin
        (api/ensure-env-exist "dev" {:prior "Library"}))
      (create-activation-key key_opt)
      (search :activation-keys searchterms)
      (let [valid-search-results (search-results-valid?
                                  (constantly true)
                                  [(:name key_opt)])]
        (verify-that (valid-search-results (extract-left-pane-list)))))
    [[{:name (uniqueify "activation_key1") :description "my auto-key" :environment "dev"} {:criteria "environment:dev*"}]
     [{:name (uniqueify "activation_key2") :description "my activation-key" :environment "dev"} {:criteria "name:activation_key2*"}]
     [{:name (uniqueify "activation_key3") :description "my activation-key" :environment "dev"} {:criteria "description:\"my activation-key\""}]
     [{:name (uniqueify "activation_key4") :description "my activation-key" :environment "dev"} {:criteria "name:activation*"}]])
          
     
  (deftest "search sync_plan"
    :data-driven true
    :description "search sync plans by default criteria i.e. name"
    (fn [key_opt searchterms]
      (create-sync-plan key_opt)
      (search :sync-plans searchterms)
      (let [valid-search-results (search-results-valid?
                                  (constantly true)
                                  [(:name key_opt)])]
        (verify-that (valid-search-results (extract-left-pane-list)))))
    [[{:name (uniqueify "new_plan1") :description "my sync plan" :interval "daily" :start-date (java.util.Date.)}  {:criteria "new_plan*"}]
     [{:name (uniqueify "new_plan2") :description "my sync plan" :interval "hourly" :start-date (java.util.Date.)} {:criteria "interval:hourly"}]
     [{:name (uniqueify "new_plan3") :description "my sync plan" :interval "weekly" :start-date (java.util.Date.)} {:criteria "description:\"my sync plan\""}]
     [{:name (uniqueify "new_plan4") :description "my sync plan" :interval "hourly" :start-date (java.util.Date.)} {:criteria "name:new_plan?*"}]]))

   (deftest "Perform search operation on system groups"
       :data-driven true
       :description "search for a system group based on criteria"
       
     (fn [system-group system searchterms]
	     (api/with-admin
          (api/ensure-env-exist "dev" {:prior "Library"}))
         (let [[name opts] system
               unique-system [(uniqueify name) opts]
               [sgname opt] system-group
               unique-sg [(uniqueify sgname) opt]]
           (apply create-system unique-system)
           (apply create-system-group unique-sg)
           (add-to-system-group (first unique-sg) (first unique-system))
           (search :system-groups searchterms)
            (let [valid-search-results (search-results-valid?
                                        (constantly true)
                                        [(first unique-sg)])]
             (let [strip-num  #(second (re-find #"(.*)\s+\(\d+\)$" %))]
              (verify-that (valid-search-results
                           (doall (map strip-num (extract-left-pane-list)))
                           ))))))
       [[["sg-fed" {:description "the centos system-group"}] ["mysystem3" {:sockets "4" :system-arch "x86_64"}] {:criteria "description: \"the centos system-group\""}]
        [["sg-fed1" {:description "the rh system-group"}] ["mysystem1" {:sockets "2" :system-arch "x86"}] {:criteria "name:sg-fed1*"}]
        [["sg-fed2" {:description "the fedora system-group"}] ["mysystem2" {:sockets "1" :system-arch "i686"}] {:criteria "system:mysystem2*"}]])