(ns katello.tests.content-search
  (:require (katello [tasks         :refer :all]
                     [content-search :as content-search]
                     [organizations :as org]
                     [providers :as providers]
                     [environments  :as env]
                     [conf          :refer [config with-org]]
                     [changesets :refer [promote-delete-content]]
                     [rest     :as rest]
                     [ui     :as ui]
                     [fake-content  :as fake])
            [katello :as kt]
            [test.assert :as assert]
            [test.tree.script :refer [defgroup deftest]]
            [clojure.set :refer [intersection difference union project select]]))

(declare test-org)
(declare test-org-compare)
(declare test-org-errata)

(def manifest-loc (tmpfile "cs-manifest.zip"))


(defn names-by-type [data-type cs-results]
  (->> cs-results
     :rows
     vals
     (filter #(= (:data_type %) data-type))
     (map :value)))

(defmacro name-fns [types]
  `(do ~@(for [t types]
           `(def ~(symbol (str t "-names")) (partial names-by-type ~t)))))

(name-fns ["repo" "product" "errata" "package"])

(defn envs [results]
  (->> results :columns (map (comp :content :to_display))))

(defn verify-compare-type  [type
                            first first-packages second second-packages]
  (let [lazy-intersect 
        (for [package (intersection first-packages second-packages)]
          [(content-search/package-in-repository? package first)
           (content-search/package-in-repository? package second)])
        lazy-only-first 
        (for [package (difference first-packages second-packages)]
          [(content-search/package-in-repository? package first)
           (not (content-search/package-in-repository? package second))])
        lazy-only-second  
        (for [package (difference second-packages first-packages)]
          [(not(content-search/package-in-repository? package first))
           (content-search/package-in-repository? package second)])]
    
    (let [test {:all [lazy-intersect lazy-only-first lazy-only-second]
                :shared [lazy-intersect]
                :unique [lazy-only-first lazy-only-second]}]
      (content-search/select-view type)
      (content-search/load-all-results)
      (every? true? (flatten (doall (test type)))))))

(defn verify-compare  [first first-packages second second-packages]
  (every? true? (doall (for [type [:all :shared :unique]]  
                         (verify-compare-type type first first-packages second second-packages)))))

(defn repo-compare-test [type first first-packages second  second-packages]
  (content-search/compare-repositories [first second])
  (content-search/select-type type)
  (verify-compare first first-packages second second-packages))


(defn repo-all-shared-different-test [type first first-packages second  second-packages]
  (let [expected-pkgs
        {:unique   (difference (union first-packages second-packages) 
                               (intersection first-packages second-packages))
         :all      (union first-packages second-packages)
         :shared   (intersection first-packages second-packages)}]
    (content-search/compare-repositories [first second])
    (content-search/select-type type)
    (every? true? (doall (for [type [:all :shared :unique]]
                           (do     
                             (content-search/select-view type)
                             (content-search/load-all-results)
                             (= (expected-pkgs type)
                                (into #{} (content-search/get-result-packages)))))))))

(defgroup content-search-repo-compare
  :group-setup (fn []
                 (def ^:dynamic test-org-compare (uniqueify  (kt/newOrganization {:name "comparesearch"})))
                 (rest/create test-org-compare)
                 (org/switch test-org-compare)
                 (fake/prepare-org-custom-provider test-org-compare fake/custom-providers)
                 (rest/create (kt/newEnvironment {:name (uniqueify "simple-env") :org test-org-compare :prior "Library"})))
  
  (deftest "Repo compare: Differences between repos can be qualified"
    :uuid "597698ba-2b7c-8274-e523-bf3c3a85124c"
    :data-driven true
    
    (fn [type repo1 repo2]
      (content-search/go-to-content-search-page test-org-compare)
      (assert/is 
       (repo-compare-test  type repo1 (into #{} (content-search/get-repo-packages repo1 :view type))
                           repo2 (into #{} (content-search/get-repo-packages repo2 :view type)))))
    
    [[:packages "CompareZoo1" "CompareZoo2"]
     [:errata "CompareZoo1" "CompareZoo2"]])
  
  (deftest "\"Compare\" UI - (SMOKE) Compare works for packages and errata"
    :uuid "96d8196d-68a2-c8b4-bf2b-9f7325056936"
    :data-driven true
    
    (fn [type repo1 repo2]
      (content-search/go-to-content-search-page test-org-compare)
      (assert/is 
       (repo-all-shared-different-test  type repo1 (into #{} (content-search/get-repo-packages repo1 :view type))
                                        repo2 (into #{} (content-search/get-repo-packages repo2 :view type)))))
    
    [[:packages "CompareZoo1" "CompareZoo2"]
     [:errata "CompareZoo1" "CompareZoo2"]])
  
  (deftest "Repo compare: Comparison against empty repository"
    :uuid "6b84c9e0-2832-aeb4-847b-1b643c00cfec"
    :data-driven true
    
    (fn [type repo1]
      (content-search/go-to-content-search-page test-org-compare)
      (assert/is (repo-compare-test  type repo1 (into #{} (content-search/get-repo-packages repo1 :view type))
                                     "CompareZooNosync" #{}))
      (assert/is (repo-all-shared-different-test type repo1 (into #{} (content-search/get-repo-packages repo1 :view type))
                                                 "CompareZooNosync" #{})))
    
    [[:packages "CompareZoo1"]
     [:errata "CompareZoo1"]])
  
  #_(deftest "Repo compare: Add and remove repos to compare"
      :uuid "8c7d782b-1682-ea44-7d9b-5247f8bf582e"
      :data-driven true
      (fn [to-add to-remove result]
        (content-search/go-to-content-search-page test-org-compare)
        (content-search/add-repositories to-add)
        (content-search/remove-repositories to-remove)
      
        (assert/is (= (content-search/get-result-repos) result)))
    
      [[["CompareZoo1" "CompareZoo2"] ["CompareZoo1"] {"Com Nature Enterprise" "CompareZoo2"}]])


  (deftest "\"Compare\" UI - User cannot submit compare without adequate repos selected"
    :uuid "ab999cde-3a94-c104-a883-e52c125f6a15"
    (content-search/go-to-content-search-page test-org-compare)
    (let [repositories ["CompareZoo1" "CompareZoo2"]]
      (content-search/add-repositories repositories)
      (assert/is (content-search/click-if-compare-button-is-disabled?))
      (content-search/check-repositories repositories)
      (assert/is (not (content-search/click-if-compare-button-is-disabled?)))))
  
  
  (deftest "\"Compare\" UI - Selecting repos for compare"
    :uuid "27a2954d-a0cd-7544-479b-d495df6869c6"
    (content-search/go-to-content-search-page test-org-compare)
    (let [repositories ["CompareZoo1" "CompareZoo2"]]
      (content-search/add-repositories (fake/get-all-custom-repo-names))
      (content-search/check-repositories repositories)
      (content-search/click-if-compare-button-is-disabled?)
      (assert/is (= (into #{} (content-search/get-repo-content-search))
                    (into #{} repositories)))))
  
  (deftest "Repo compare: Add many repos to compare"
    :uuid "096be600-4d8c-cb64-9993-8d8fac51fce7"
    (content-search/go-to-content-search-page test-org-compare)
    (let [repos (difference (set (fake/get-all-custom-repo-names)) (set (fake/get-i18n-repo-names)))]
      (assert/is (= repos
                    (set (content-search/compare-repositories (into [] repos)))))))
  
  (deftest "Content Search: search repo info"
    :uuid "05955468-e85c-1f74-8c9b-a1fd152cfb70"
    :data-driven true
    (fn [repo-search expected-repos]
      (content-search/go-to-content-search-page test-org-compare)
      (assert/is (= expected-repos (content-search/search-for-repositories repo-search))))
    
    [["Co*"  {"Default Organization View" 
              {"Com Nature Enterprise" 
               #{"CompareZooNosync" "CompareZoo1" "CompareZoo2"}}}]
     ["Many*" {"Default Organization View" 
               #{{"Com Nature Enterprise" "ManyRepositoryA"} 
                 {"ManyRepository Enterprise" #{"ManyRepositoryA" "ManyRepositoryB" "ManyRepositoryC" "ManyRepositoryD" "ManyRepositoryE"}}}}]
     ["CompareZoo1" {"Default Organization View" 
                     {"Com Nature Enterprise" "CompareZoo1"}}]
     ["Russia" {"Default Organization View" 
                {"Weird Enterprise" "Russia"}}]])

  (deftest "Content Search: search package info"
    :uuid "d96759d7-b13d-3554-a67b-5453ca05d3e3"
    :data-driven true
    
    (fn [pkg-search expected-pkgs]
      (content-search/go-to-content-search-page test-org-compare)
      (do
        (assert/is (= expected-pkgs (content-search/search-for-packages pkg-search)))))     
    [["bear-4.1-1.noarch" {"Default Organization View" 
                           #{{"Weird Enterprise" {"Russia" #{"4.1-1.noarch" "bear"}}} 
                             {"Com Nature Enterprise" {"CompareZoo2" #{"4.1-1.noarch" "bear"}}}}}]
     
     ["s*" {"Default Organization View" 
            #{{"ManyRepository Enterprise" 
               #{{"ManyRepositoryE" #{"0.3-0.8.noarch" "squirrel"}}
                 {"ManyRepositoryA" #{"0.3-0.8.noarch" "squirrel"}} 
                 {"ManyRepositoryB" #{"0.3-0.8.noarch" "squirrel"}} 
                 {"ManyRepositoryC" #{"0.3-0.8.noarch" "squirrel"}} 
                 {"ManyRepositoryD" #{"0.3-0.8.noarch" "squirrel"}}}}
              {"Weird Enterprise" 
               #{{"China" #{"0.3-0.8.noarch" "squirrel"}} 
                 {"Russia" #{#{"0.1-1.noarch" "squirrel"} 
                             #{"0.12-2.noarch" "stork"} 
                             #{"shark" "0.1-1.noarch"}}}}} 
              {"Com Nature Enterprise" 
               #{{"CompareZoo1" #{"0.3-0.8.noarch" "squirrel"}} 
                 {"CompareZoo2" #{#{"0.1-1.noarch" "squirrel"} 
                                  #{"0.12-2.noarch" "stork"} 
                                  #{"shark" "0.1-1.noarch"}}}}}}}]]))

(defn verify-errata [type expected-errata]
  (content-search/go-to-content-search-page test-org-errata)
  (assert/is (= (if (= expected-errata #{}) ; I don't need to add prefix if result is empty
                  #{}
                 {"Default Organization View" {"Com Errata Enterprise" {"ErrataZoo" expected-errata}}} )
                (content-search/get-errata-set type))))

(defmacro deftests-errata-search
  "for a bunch of data driven tests that use the same function, but
   different name and data."
  [& tests]
  `(concat ~(vec (for [{:keys [name uuid data]} tests]
                   `(deftest ~name
                      :uuid ~uuid
                      :data-driven true

                      verify-errata
                      ~data)))))


(def errata-search-table
  (let [[header & table] [[:errata :title :type :severity]
                          ["RHEA-2012:2010" "Squirrel_Erratum" "enhancement" "low"]
                          ["RHEA-2012:2011" "Camel_Erratum" "security" "important"]
                          ["RHEA-2012:2012" "Dog_Erratum" "security" "critical"]
                          ["RHEA-2012:2013" "Cow_Erratum" "bugfix" "moderate"]]]
  (map (partial zipmap header) table)))

(defgroup content-search-errata
  :group-setup (fn []
                 (def ^:dynamic test-org-errata (uniqueify  (kt/newOrganization {:name"erratasearch"})))
                 (rest/create test-org-errata)
                 (org/switch test-org-errata)
                 (fake/prepare-org-custom-provider test-org-errata fake/custom-errata-test-provider)
                 (rest/create (kt/newEnvironment {:name (uniqueify "simple-env") :org test-org-errata :prior-env "Library"})))
  
  (deftest "Content Browser: Errata information"
    :uuid "f2a008f7-3219-1934-0c1b-82f47633be1c"
    (content-search/go-to-content-search-page test-org-errata)
    (content-search/get-errata-set "*")
    (content-search/test-errata-popup-click "RHEA-2012:2011")
    (content-search/add-repositories ["ErrataZoo"])
    (content-search/click-repo-errata "ErrataZoo")
    (content-search/test-errata-popup-click "RHEA-2012:2011")
    (content-search/compare-repositories ["ErrataZoo"])
    (content-search/select-type :errata)
    (content-search/test-errata-popup-click "RHEA-2012:2011"))
  
  (deftests-errata-search
    {:name "UI - Search Errata in Content Search by exact Errata"
     :uuid "2ae5acbb-2765-8bf4-6753-6f36e83a0844"
     :data [["\"RHEA-2012:2013\"" "RHEA-2012:2013"] 
            ["\"RHEA-2012:2012\"" "RHEA-2012:2012"] 
            ["\"RHEA-2012:2011\"" "RHEA-2012:2011"] 
            ["\"RHEA-2012:2010\"" "RHEA-2012:2010"]]}

    {:name "UI - Search Errata in Content Search by exact title"
     :uuid "f726fc85-13df-a444-580b-421e56b314d3"
     :data [["title:\"Squirrel_Erratum\"""RHEA-2012:2010"]
            ["title:\"Camel_Erratum\"" "RHEA-2012:2011"]
            ["title:\"Dog_Erratum\"" "RHEA-2012:2012"]
            ["title:\"Cow_Erratum\"" "RHEA-2012:2013"]]}
    
    {:name "UI - Search Errata in Content Search by title regexp"
     :uuid "644c9e0c-6cca-5104-82eb-0bf850170f09"
     :data [["title:Squirrel_*" "RHEA-2012:2010"]
            ["title:Cam*" "RHEA-2012:2011"]
            ["title:Dog*" "RHEA-2012:2012"]
            ["title:Co*" "RHEA-2012:2013"]
            ["title:*o*" #{"RHEA-2012:2012" "RHEA-2012:2013"}]]}
    
    {:name "UI - Search Errata in Content Search by type regexp"
     :uuid "fee0c389-ba8b-0ce4-66d3-8927301dd2c4"
     :data [["type:secur*" #{"RHEA-2012:2011" "RHEA-2012:2012"}]
            ["type:*ug*" "RHEA-2012:2013"]
            ["type:*ement" "RHEA-2012:2010"]
            ["type:ttt" #{}]
            ["type:" #{}]]}
    
    {:name "UI - Search Errata in Content Search by type"
     :uuid "817bbd79-2e48-d0a4-1173-2afdfb28a8b2"
     :data [["type:security" #{"RHEA-2012:2011" "RHEA-2012:2012"}]
            ["type:bugfix" "RHEA-2012:2013"]
            ["type:enhancement" "RHEA-2012:2010"]]}
    
    {:name "UI - Search Errata in Content Search by severity"
     :uuid "a20b0c77-a707-f504-e53b-935314b460d6"
     :data [["severity:low" "RHEA-2012:2010"]
            ["severity:important" "RHEA-2012:2011"]
            ["severity:critical" "RHEA-2012:2012"]
            ["severity:moderate" "RHEA-2012:2013"]
            ["severity:l*" "RHEA-2012:2010"]
            ["severity:*rtant" "RHEA-2012:2011"]
            ["severity:*cal" "RHEA-2012:2012"]
            ["severity:mod*" "RHEA-2012:2013"]
            ["severity:ttt" #{}]
            ["severity:" #{}]]}))


(defgroup content-search-tests
  content-search-repo-compare
  content-search-errata)

