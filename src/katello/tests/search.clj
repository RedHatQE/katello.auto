(ns katello.tests.search
  (:require [katello :as kt]
            (katello [ui :as ui]
                     [rest :as rest]
                     [organizations :as organization]
                     environments
                     [users :as user]
                     [ui-common :refer [search extract-left-pane-list]]
                     [sync-management :as sync]
                     [tasks :refer :all]
                     [system-groups :as sg]
                     [activation-keys :as ak]
                     [systems :as system]
                     [conf :refer [*session-org* config]])
            
            [katello.tests.useful :refer [ensure-exists chained-env]]
            [test.tree.script :refer :all]
            [test.assert :as assert]
            [bugzilla.checker :refer [open-bz-bugs]]
            [slingshot.slingshot :refer :all]))


;; Functions


(defn search-results-valid?
  "Returns a predicate that will be true when the search results both
   contain expected-items and each result item matches the provided
   predicate."
  [pred expected-items]
  (fn [results]
    (boolean (and (every? pred results)
                  (every? (into #{} results) expected-items)))))

(defn validate-search-results [expected-items]
  (assert/is (every? (set (extract-left-pane-list)) (map :name expected-items))))

;; Tests
(defgroup search-tests
  
  (deftest "Perform search operation on systems"
    :data-driven true
    :description "Search for a system based on criteria."
    :blockers rest/katello-only
    (fn [sysinfo searchterms & [groupinfo]]
      (with-unique [env (chained-env {:name "dev", :org *session-org*})
                    system (kt/newSystem (assoc sysinfo :env env))
                    sg (kt/newSystemGroup (assoc groupinfo :org *session-org*))]
        ;; update hostname in facts to match uniquified system name
        (let [system (update-in system [:facts] assoc "network.hostname" (:name system))]
          (ensure-exists env)
         (rest/create system) 
         (when groupinfo
           (ui/update system assoc :description "most unique system")
           (ui/create sg)
           (ui/update sg assoc :systems (list system)))
         (search system searchterms)
         (validate-search-results (list system)))))
    
    [[{:name "mysystem3", :sockets "4", :system-arch "x86_64"} {:criteria "description: \"most unique system\""} {:name "fed", :description "centos system-group"}]
     [{:name "mysystem3", :sockets "2", :system-arch "x86"} {:criteria "system_group:fed1*"} {:name "fed1", :description "rh system-group"}]
     [{:name "mysystem1", :sockets "1", :system-arch "x86_64"} {:criteria "name:mysystem1*"}]
     [{:name "mysystem-123", :sockets "1", :system-arch "x86"} {:criteria "network.hostname:mysystem-123*"}]
     [{:name "mysystem2", :sockets "2", :system-arch "i686"} {:criteria "mysystem2*"}]])


  (deftest "Search organizations"
    :data-driven true
    :description "Search for organizations based on criteria." 
    
    (fn [orginfo searchterms]
      (with-unique [org (kt/newOrganization orginfo)]
        (ui/create org)
        (search org searchterms)
        (validate-search-results (list org))))

    (let [dev-env (kt/newEnvironment {:name "dev"})
          test-env (kt/newEnvironment {:name "test"})]
      (concat
             ;;'normal' org searches
     
             [[{:name "test123" :initial-env test-env :description "This is a test123 org"} {:criteria "test123*"}]
              [{:name "test-123" :initial-env dev-env :description "This is a test-123 org"} {:criteria "name:test-123*"}]
              [{:name "test" :initial-env dev-env :description "This is a test org"} {:criteria "description:\"This is a test org\""}]
              [{:name "test" :initial-env dev-env :description "This is a test org"} {:criteria "description:(+test+org)"}]
              (with-meta
                [{:name "test" :initial-env dev-env :description "This is a test org"} {:criteria "environment:dev*"}]
                {:blockers (open-bz-bugs "852119")})]

             ;;with latin-1/multibyte searches
     
             (for [row
                   [[{:name "niños"  :initial-env test-env :description "This is a test org with latin charcters in name"} {:criteria "niños*"}]
                    [{:name "bilingüe" :description "This is a test org with spanish characters like bilingüe" :initial-env test-env}  {:criteria "bilingüe*"}]
                    [{:name "misión"  :description "This is a test org with spanish char misión,biños  " :initial-env dev-env} {:criteria "misión*"}]
                    [{:name "biños" :description "This is a test_123 org" :initial-env dev-env}  {:criteria "name:?iños*"}]
                    [{:name "misión"  :description "This is a test org with spanish char misión,biños " :initial-env dev-env} {:criteria "description:\"This is a test org with spanish char misión,biños\""}]
                    [{:name "test_华语華語"  :description "This is a test org with multi-byte charcters in name" :initial-env test-env} {:criteria "test_华语華語*"}]
                    [{:name "兩千三百六十二" :description "This is a test org with multi-byte characters like తెలుగు" :initial-env test-env} {:criteria "兩千三百六十二*"}]
                    [{:name "hill_山"  :description "This is a test org with multi-byte char like hill_山  兩千三百六十二, test_华语華語" :initial-env dev-env} {:criteria "description:\"This is a test org with multi-byte char like hill_山  兩千三百六十二, test_华语華語\""}]
                    [{:name "తెలుగు" :description "This is a test_123 org" :initial-env dev-env} {:criteria "తెలుగు*"}]]]


               ;;modify each above row with metadata specific to multibyte
               ;;testing
       
               (with-meta row
                 {:blockers (open-bz-bugs "832978")
                  :description "Search for organizations names including
                        latin-1/multi-byte characters in search
                        string."})))))
  
  
  (deftest "search users"
    :data-driven true
    :description "Search for a user based on criteria and with use of lucene-syntax" 

    (fn [userinfo searchterms]
      (with-unique [user (kt/newUser userinfo)]
        (ui/create user)
        (search user searchterms)
        (validate-search-results (list user))))
    
    [[{:name "username1" :password "password" :email "username1@my.org"} {:criteria "username1*"}]
     [{:name "username2" :password "password" :email "username2@my.org"} {:criteria "username:username?*"}]
     [{:name "lucene4"   :password "password" :email "lucene4@my.org"} {:criteria "email:\"*@my.org\""}]
     [{:name "lucene5"   :password "password" :email "lucene5@my.org"} {:criteria "email:@my.org"}]
     [{:name "lucene6"   :password "password" :email "lucene6@my.org"} {:criteria "email:my.org"}]])
  
  (deftest "search activation keys"
    :data-driven true
    :description "search activation keys by default criteria i.e. name"
    
    (fn [akinfo searchterms]
      (with-unique [env (chained-env {:name "dev", :org *session-org*})
                    ak (kt/newActivationKey (assoc akinfo :env env))]
        (rest/create env)
        (ui/create ak)
        (search ak searchterms)
        (validate-search-results (list ak))))
    
    [[{:name "activation_key1" :description "my auto-key"} {:criteria "environment:dev*"}]
     [{:name "activation_key2" :description "my activation-key"} {:criteria "name:activation_key2*"}]
     [{:name "activation_key3" :description "my activation-key"} {:criteria "description:\"my activation-key\""}]
     [{:name "activation_key4" :description "my activation-key"} {:criteria "name:activation*"}]])
  
  
  (deftest "search sync plans"
    :data-driven true
    :description "search sync plans by default criteria i.e. name"
    :blockers rest/katello-only
    
    (fn [planinfo searchterms]
      (with-unique [plan (kt/newSyncPlan (assoc planinfo :org *session-org*))]
        (ui/create plan)
        (search plan searchterms)
        (validate-search-results (list plan))))
    
    [[{:name "new_plan1" :description "my sync plan" :interval "daily" :start-date (java.util.Date.)}  {:criteria "new_plan*"}]
     [{:name "new_plan2" :description "my sync plan" :interval "hourly" :start-date (java.util.Date.)} {:criteria "interval:hourly"}]
     [{:name "new_plan3" :description "my sync plan" :interval "weekly" :start-date (java.util.Date.)} {:criteria "description:\"my sync plan\""}]
     [{:name "new_plan4" :description "my sync plan" :interval "hourly" :start-date (java.util.Date.)} {:criteria "name:new_plan?*"}]])

  (deftest "search system groups"
    :data-driven true
    :description "search for a system group based on criteria"
    :blockers rest/katello-only
    
    (fn [groupinfo sysinfo searchterms]
      (with-unique [env (chained-env {:name "dev", :org *session-org*})
                    system (kt/newSystem (assoc sysinfo :env env))
                    sg (kt/newSystemGroup (assoc groupinfo :org *session-org*))]
        (ensure-exists env)
        (ui/create-all (list system sg))
        (ui/update sg assoc :systems (list system))
        (search sg searchterms)
        (let [validate-search-results (search-results-valid?
                                       (constantly true)
                                       (list sg))]
          (let [strip-num  #(second (re-find #"(.*)\s+\(\d+\)$" %))]
            (assert/is (validate-search-results
                        (doall (map strip-num (extract-left-pane-list)))))))))
    [[{:name "sg-fed" :description "the centos system-group"} {:name "mysystem3" :sockets "4" :system-arch "x86_64"} {:criteria "description: \"the centos system-group\""}]
     [{:name "sg-fed1" :description "the rh system-group"} {:name "mysystem1" :sockets "2" :system-arch "x86"} {:criteria "name:sg-fed1*"}]
     [{:name "sg-fed2" :description "the fedora system-group"} {:name "mysystem2" :sockets "1" :system-arch "i686"} {:criteria "system:mysystem2*"}]])

  (deftest "search GPG keys"
    :data-driven true
    :description "search GPG keys by default criteria i.e. name"
    
    (fn [gpg-key-info searchterms]
      (with-unique [key (kt/newGPGKey (assoc gpg-key-info :org *session-org*))]
        (ui/create key)
        (search key searchterms)
        (validate-search-results (list key))))
    [[{:name "gpg_key1" :contents "gpgkeys1234"} {:criteria "content:\"gpgkeys1234\""}]
     (fn [] [{:name "gpg_key2" :contents (slurp (@config :gpg-key))} {:criteria "name:gpg_key2*"}])
     (fn [] [{:name "gpg_key3" :contents (slurp (@config :gpg-key))} {:criteria "name:gpg_key*"}])]))
