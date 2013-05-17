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
                     [fake-content  :as fake]
            )
            [katello :as kt]
            [test.assert :as assert]
            [bugzilla.checker :refer [open-bz-bugs]]
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
    :data-driven true
    
    (fn [type repo1 repo2]
      (content-search/go-to-content-search-page test-org-compare)
      (assert/is 
       (repo-compare-test  type repo1 (into #{} (content-search/get-repo-packages repo1 :view type))
                           repo2 (into #{} (content-search/get-repo-packages repo2 :view type)))))
    
    [[:packages "CompareZoo1" "CompareZoo2"]
     [:errata "CompareZoo1" "CompareZoo2"]])
  
  (deftest "\"Compare\" UI - (SMOKE) Compare works for packages and errata"
    :data-driven true
    
    (fn [type repo1 repo2]
      (content-search/go-to-content-search-page test-org-compare)
      (assert/is 
       (repo-all-shared-different-test  type repo1 (into #{} (content-search/get-repo-packages repo1 :view type))
                                        repo2 (into #{} (content-search/get-repo-packages repo2 :view type)))))
    
    [[:packages "CompareZoo1" "CompareZoo2"]
     [:errata "CompareZoo1" "CompareZoo2"]])
  
  (deftest "Repo compare: Comparison against empty repository"
    :data-driven true
    
    (fn [type repo1]
      (content-search/go-to-content-search-page test-org-compare)
      (assert/is (repo-compare-test  type repo1 (into #{} (content-search/get-repo-packages repo1 :view type))
                                     "CompareZooNosync" #{}))
      (assert/is (repo-all-shared-different-test type repo1 (into #{} (content-search/get-repo-packages repo1 :view type))
                                                 "CompareZooNosync" #{})))
    
    [[:packages "CompareZoo1"]
     [:errata "CompareZoo1"]])
  
(comment  
  (deftest "Repo compare: Add and remove repos to compare"
    :data-driven true
    (fn [to-add to-remove result]
      (content-search/go-to-content-search-page test-org-compare)
      (content-search/add-repositories to-add)
      (content-search/remove-repositories to-remove)
      
      (assert/is (= (content-search/get-result-repos) result)))
    
    [[["CompareZoo1" "CompareZoo2"] ["CompareZoo1"] {"Com Nature Enterprise" "CompareZoo2"}]])
)

  (deftest "\"Compare\" UI - User cannot submit compare without adequate repos selected"
    (content-search/go-to-content-search-page test-org-compare)
    (let [repositories ["CompareZoo1" "CompareZoo2"]]
      (content-search/add-repositories repositories)
      (assert/is (content-search/click-if-compare-button-is-disabled?))
      (content-search/check-repositories repositories)
      (assert/is (not (content-search/click-if-compare-button-is-disabled?)))))
  
  
  (deftest "\"Compare\" UI - Selecting repos for compare"
    (content-search/go-to-content-search-page test-org-compare)
    (let [repositories ["CompareZoo1" "CompareZoo2"]]
      (content-search/add-repositories (fake/get-all-custom-repo-names))
      (content-search/check-repositories repositories)
      (content-search/click-if-compare-button-is-disabled?)
      (assert/is (= (into #{} (content-search/get-repo-content-search))
                    (into #{} repositories)))))
  
  (deftest "Repo compare: Add many repos to compare"
    (content-search/go-to-content-search-page test-org-compare)
    (let [repos (difference (set (fake/get-all-custom-repo-names)) (set (fake/get-i18n-repo-names)))]
      (assert/is (= repos
                   (set (content-search/compare-repositories (into [] repos)))))))
  
  (deftest "Content Search: search repo info"
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
    :data-driven true
    
    (fn [pkg-search expected-pkgs lib-cp]
      (content-search/go-to-content-search-page test-org-compare)
      (do
        (assert/is (= expected-pkgs (content-search/search-for-packages pkg-search)))))     
    [["bear-4.1-1.noarch" {"Default Organization View" 
                           #{{"Weird Enterprise" 
                              {"Russia" #{"4.1-1.noarch" "bear"}}} 
                             {"Com Nature Enterprise" 
                              {"CompareZoo2" #{"4.1-1.noarch" "bear"}}}}}
      
      {:first "Library" :fpackages ["Nature" "Weird"]
       :second "simple-env" :spackages ["Nature"]}]
     
     ["s*" {"Default Organization View" 
						 #{{"ManyRepository Enterprise" 
						    #{{"ManyRepositoryE" #{"0.3-0.8.noarch" "squirrel"}}
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
						      {"ManyRepositoryA" #{"0.3-0.8.noarch" "squirrel"}} 
						      {"CompareZoo2" #{#{"0.1-1.noarch" "squirrel"} 
						                       #{"0.12-2.noarch" "stork"} 
						                       #{"shark" "0.1-1.noarch"}}}}}}}
						      
						      {:first "Library" :fpackages ["Nature" "Weird" "Many"]
       :second "simple-env" :spackages ["Nature"]}]]))

(defn verify-errata [type expected-errata]
  (content-search/go-to-content-search-page test-org-errata)
  (assert/is (= (if (= expected-errata #{}) ; I don't need to add prefix if result is empty
                  #{}
                 {"Default Organization View" {"Com Errata Enterprise" {"ErrataZoo" expected-errata}}} )
                (content-search/get-errata-set type))))

(defmacro deftests-errata-search
  "for a bunch of data driven tests that use the same function, but
   different name and data."
  [name-data-map]
  `(concat ~(vec (for [[name data] name-data-map]
                   `(deftest ~name
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
    {"UI - Search Errata in Content Search by exact Errata"
     [["\"RHEA-2012:2013\"" "RHEA-2012:2013"] 
      ["\"RHEA-2012:2012\"" "RHEA-2012:2012"] 
      ["\"RHEA-2012:2011\"" "RHEA-2012:2011"] 
      ["\"RHEA-2012:2010\"" "RHEA-2012:2010"]]

     "UI - Search Errata in Content Search by exact title"
     [["title:\"Squirrel_Erratum\"""RHEA-2012:2010"]
      ["title:\"Camel_Erratum\"" "RHEA-2012:2011"]
      ["title:\"Dog_Erratum\"" "RHEA-2012:2012"]
      ["title:\"Cow_Erratum\"" "RHEA-2012:2013"]]
     
     "UI - Search Errata in Content Search by title regexp"
     [["title:Squirrel_*" "RHEA-2012:2010"]
      ["title:Cam*" "RHEA-2012:2011"]
      ["title:Dog*" "RHEA-2012:2012"]
      ["title:Co*" "RHEA-2012:2013"]
      ["title:*o*" #{"RHEA-2012:2012" "RHEA-2012:2013"}]]
     
     "UI - Search Errata in Content Search by type regexp"
     [["type:secur*" #{"RHEA-2012:2011" "RHEA-2012:2012"}]
      ["type:*ug*" "RHEA-2012:2013"]
      ["type:*ement" "RHEA-2012:2010"]
      ["type:ttt" #{}]
      ["type:" #{}]]
     
     "UI - Search Errata in Content Search by type"
     [["type:security" #{"RHEA-2012:2011" "RHEA-2012:2012"}]
      ["type:bugfix" "RHEA-2012:2013"]
      ["type:enhancement" "RHEA-2012:2010"]]
     
     "UI - Search Errata in Content Search by severity"
     [["severity:low" "RHEA-2012:2010"]
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

