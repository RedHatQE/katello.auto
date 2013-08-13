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
                     [changesets :as changeset]
                     [conf :refer [*session-org* config]]
                     [content-view-definitions :as views]
                     [blockers :refer [bz-bugs auto-issue]])
            [katello.tests.useful :refer [ensure-exists fresh-repo create-recursive add-product-to-cv]]
            (test.tree [script :refer :all])
            [test.assert :as assert]
            [slingshot.slingshot :refer :all]))


;; Functions


(defn search-results-valid?
  "Returns a predicate that will be true when the search results both
   contain expected-items and each result item matches the provided
   predicate."
  [pred expected-items]
  (fn [results]
    (println expected-items)
    (boolean (and (every? pred results)
                  (every? (into #{} results) (map :name expected-items))))))

(defn validate-search-results [expected-items]
  (assert/is (every? (set (extract-left-pane-list)) (map :name expected-items))))

;; Tests
(defgroup search-tests
  
  (deftest "Perform search operation on systems"
    :uuid "98a8bcdc-5e66-6cb4-8683-f7a141fbce30"
    :data-driven true
    :description "Search for a system based on criteria."
    :blockers (list rest/katello-only)
    (fn [sysinfo searchterms & [groupinfo]]
      (with-unique [env (kt/newEnvironment {:name "dev", :org *session-org*})
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
    :uuid "f6b04936-b114-df74-c29b-d280a0fc3b2d"
    :data-driven true
    :description "Search for organizations based on criteria." 
    
    (fn [orginfo searchterms]
      (with-unique [org (kt/newOrganization orginfo)]
        (create-recursive org)
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
                {:blockers (conj (bz-bugs "852119") rest/katello-only (auto-issue "792"))})]

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
                 {:blockers (bz-bugs "832978")
                  :description "Search for organizations names including
                        latin-1/multi-byte characters in search
                        string."})))))
  
  
  (deftest "search users"
    :uuid "d999d3cb-7fc7-5524-610b-f7a03d5fa84c"
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
    :uuid "f7f0d6e8-88ae-c964-69eb-65fd0a3351e5"
    :data-driven true
    :description "search activation keys by default criteria i.e. name"   
    (fn [akinfo searchterms]
      (with-unique [org (kt/newOrganization {:name "cv-org"})
                    target-env (kt/newEnvironment {:name "dev" :org org})]
        (let [repo (fresh-repo org "http://inecas.fedorapeople.org/fakerepos/cds/content/safari/1.0/x86_64/rpms/")
              cv (rest/when-katello (add-product-to-cv org target-env repo))
              ak (kt/newActivationKey (assoc akinfo :env target-env
                                                    :content-view cv))
              cs (kt/newChangeset {:name "cs"
                                   :env target-env
                                   :content (list cv)})]
          (if (rest/is-katello?) 
            (do 
              (views/publish {:content-defn cv
                              :published-name (:published-name cv)
                              :org org})
              (changeset/promote-delete-content cs))
            (ui/create org))
          (ui/create ak)
          (search ak searchterms)
          (validate-search-results (list ak)))))
    
    [(with-meta
       [{:name "activation_key1" :description "my auto-key"} {:criteria "environment:dev*"}]
       {:blockers (list rest/katello-only)})
     [{:name "activation_key2" :description "my activation-key"} {:criteria "name:activation_key2*"}]
     [{:name "activation_key3" :description "my activation-key"} {:criteria "description:\"my activation-key\""}]
     [{:name "activation_key4" :description "my activation-key"} {:criteria "name:activation*"}]])
  
  (deftest "search sync plans"
    :uuid "327872ef-0576-b5c4-eac3-d5b035e95b11"
    :data-driven true
    :description "search sync plans by default criteria i.e. name"
    :blockers (list rest/katello-only)
    
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
    :uuid "0fe3467b-2861-08d4-11fb-66091adeede7"
    :data-driven true
    :description "search for a system group based on criteria"
    :blockers (list rest/katello-only)
    
    (fn [groupinfo sysinfo searchterms]
      (with-unique [env (kt/newEnvironment {:name "dev", :org *session-org*})
                    system (kt/newSystem (assoc sysinfo :env env))
                    sg (kt/newSystemGroup (assoc groupinfo :org *session-org*))]
        (ensure-exists env)
        (rest/create-all (list system sg))
        (ui/update sg assoc :systems (list system))
        (search sg searchterms)
        (let [strip-num  #(second (re-find #"(.*)\s+\(\d+\)$" %))
              sgs-in-results (doall (map strip-num (extract-left-pane-list)))]
          (assert/is ((set sgs-in-results) (:name sg))))))
    [[{:name "sg-fed" :description "the centos system-group"} {:name "mysystem3" :sockets "4" :system-arch "x86_64"} {:criteria "description: \"the centos system-group\""}]
     [{:name "sg-fed1" :description "the rh system-group"} {:name "mysystem1" :sockets "2" :system-arch "x86"} {:criteria "name:sg-fed1*"}]
     [{:name "sg-fed2" :description "the fedora system-group"} {:name "mysystem2" :sockets "1" :system-arch "i686"} {:criteria "system:mysystem2*"}]])

  (deftest "search GPG keys"
    :uuid "000d192b-068e-ca64-456b-207f87b14f52"
    :data-driven true
    :description "search GPG keys by default criteria i.e. name"
    :blockers (list rest/katello-only)
    
    (fn [gpg-key-info searchterms]
      (with-unique [key (kt/newGPGKey (assoc gpg-key-info :org *session-org*))]
        (ui/create key)
        (search key searchterms)
        (validate-search-results (list key))))
    [[{:name "gpg_key1" :contents "gpgkeys1234"} {:criteria "content:\"gpgkeys1234\""}]
     (fn [] [{:name "gpg_key2" :contents (slurp (@config :gpg-key))} {:criteria "name:gpg_key2*"}])
     (fn [] [{:name "gpg_key3" :contents (slurp (@config :gpg-key))} {:criteria "name:gpg_key*"}])]))
