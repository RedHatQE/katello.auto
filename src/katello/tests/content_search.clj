(ns katello.tests.content-search
  (:require (katello [tasks         :refer :all]
                     [content-search :as content-search]
                     [organizations :as org]
                     [environments  :as env]
                     [manifest      :as manifest]
                     [conf          :refer [config with-org]]
                     [api-tasks     :as api]
                     [fake-content  :as fake])
            [test.assert :as assert]
            [bugzilla.checker :refer [open-bz-bugs]]
            [test.tree.script :refer [defgroup deftest]]
            [clojure.set :refer [intersection difference union]]))

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
                           first first-packages second second-packages ]
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
                 (def ^:dynamic test-org-compare (uniqueify "comparesearch"))
                 (api/create-organization test-org-compare)
                 (org/switch test-org-compare)
                 (fake/prepare-org-custom-provider test-org-compare fake/custom-providers)
                 (env/create (uniqueify "simple-env") {:org-name test-org-compare :prior-env "Library"}))
    
  (deftest "Repo compare: Differences between repos can be qualified"
      :data-driven true
      
      (fn [type repo1 repo2]
        (org/switch test-org-compare)
        (assert/is 
          (repo-compare-test  type repo1 (into #{} (content-search/get-repo-packages repo1 :view type))
                                 repo2 (into #{} (content-search/get-repo-packages repo2 :view type)))))
      
      [[:packages "CompareZoo1" "CompareZoo2"]
       [:errata "CompareZoo1" "CompareZoo2"]])
  
  (deftest "\"Compare\" UI - (SMOKE) Compare works for packages and errata"
      :data-driven true
      
      (fn [type repo1 repo2]
        (org/switch test-org-compare)
        (assert/is 
            (repo-all-shared-different-test  type repo1 (into #{} (content-search/get-repo-packages repo1 :view type))
                                                  repo2 (into #{} (content-search/get-repo-packages repo2 :view type)))))
      
      [[:packages "CompareZoo1" "CompareZoo2"]
       [:errata "CompareZoo1" "CompareZoo2"]])
  
   (deftest "Repo compare: Comparison against empty repository"
      :data-driven true
      
      (fn [type repo1]
        (org/switch test-org-compare)
        (assert/is (repo-compare-test  type repo1 (into #{} (content-search/get-result-packages repo1 :view type))
                                 "CompareZooNosync" #{}))
        (assert/is (repo-all-shared-different-test type repo1 (into #{} (content-search/get-result-packages repo1 :view type))
                                 "CompareZooNosync" #{})))
      
      [[:packages "CompareZoo1"]
       [:errata "CompareZoo1"]])
      
   
    (deftest "Repo compare: Add and remove repos to compare"
      :data-driven true
      (fn [to-add to-remove result]
        (org/switch test-org-compare)
        (content-search/compare-repositories to-add)
        (content-search/remove-repositories to-remove)
      
          (assert/is (= (content-search/get-result-repos) result)))
            
        [[["CompareZoo1" "CompareZoo2"] ["CompareZoo1"] {"Com Nature Enterprise" "CompareZoo2"}]])
    
    (deftest "\"Compare\" UI - User cannot submit compare without adequate repos selected"
      (org/switch test-org-compare)
      (let [repositories ["CompareZoo1" "CompareZoo2"]]
        (content-search/add-repositories repositories)
        (assert/is (content-search/click-if-compare-button-is-disabled?))
        (content-search/check-repositories repositories)
        (assert/is (not (content-search/click-if-compare-button-is-disabled?)))))
  
    
    (deftest "\"Compare\" UI - Selecting repos for compare"
      (org/switch test-org-compare)
      (let [repositories ["CompareZoo1" "CompareZoo2"]]
        (content-search/add-repositories (fake/get-all-custom-repos))
        (content-search/check-repositories repositories)
        (content-search/click-if-compare-button-is-disabled?)
        (assert/is (= (content-search/get-result-repos) 
                      {"Com Nature Enterprise" #{"CompareZoo1" "CompareZoo2"}}))))
     
    (deftest "Repo compare: Add many repos to compare"
      (org/switch test-org-compare)
      (let [repos (difference (set (fake/get-all-custom-repos)) (set (fake/get-i18n-repos)))]
        (assert/is (= repos
                        (set (content-search/compare-repositories (into [] repos)))))))
  
    (deftest "Repo compare: repos render correctly when internationalized"
      (org/switch test-org-compare)
      (let [expected (set (fake/get-i18n-repos))
            result (set (content-search/compare-repositories expected))]
            (assert/is (= expected result))))    

    (deftest "Content Search: search repo info"
      :data-driven true
      (fn [repo-search expected-repos]
          (org/switch test-org-compare)
          (assert/is (= expected-repos (content-search/search-for-repositories repo-search))))
      
       [["Co*"  {"Com Nature Enterprise" 
                     #{"CompareZooNosync" "CompareZoo1" "CompareZoo2"}}]
       ["Many*" {"ManyRepository Enterprise" 
                     #{"ManyRepositoryA" "ManyRepositoryB" "ManyRepositoryC" "ManyRepositoryD" "ManyRepositoryE"}, 
                 "Com Nature Enterprise" 
                     "ManyRepositoryA"}]
       ["CompareZoo1" {"Com Nature Enterprise" 
                           "CompareZoo1"}]
       ["Гесер" {"WeirdLocalsUsing 標準語 Enterprise" 
                     "Гесер"}]])

    (deftest "Content Search: search package info"
      :data-driven true
      
      (fn [pkg-search expected-pkgs lib-cp]
        (org/switch test-org-compare)
        (do
          (assert/is (= expected-pkgs (content-search/search-for-packages pkg-search)))))     
       [["bear-4.1-1.noarch" {"WeirdLocalsUsing 標準語 Enterprise" {"Гесер"  #{"4.1-1.noarch" "bear"}},
                              "Com Nature Enterprise" {"CompareZoo2" #{"4.1-1.noarch" "bear"}}}
                             {:first "Library" :fpackages ["Nature" "Weird"]
                              :second "simple-env" :spackages ["Nature"]}]
        
       ["s*" {"WeirdLocalsUsing 標準語 Enterprise"
              {"Гесер"
               #{#{"0.1-1.noarch" "squirrel"} #{"0.12-2.noarch" "stork"}
                 #{"shark" "0.1-1.noarch"}},
               "洪" #{"0.3-0.8.noarch" "squirrel"}},
              "ManyRepository Enterprise"
              {"ManyRepositoryA" #{"0.3-0.8.noarch" "squirrel"},
               "ManyRepositoryB" #{"0.3-0.8.noarch" "squirrel"},
               "ManyRepositoryC" #{"0.3-0.8.noarch" "squirrel"},
               "ManyRepositoryD" #{"0.3-0.8.noarch" "squirrel"}
               "ManyRepositoryE" #{"0.3-0.8.noarch" "squirrel"}},
              "Com Nature Enterprise"
              {"CompareZoo1" #{"0.3-0.8.noarch" "squirrel"},
               "CompareZoo2"
               #{#{"0.1-1.noarch" "squirrel"} #{"0.12-2.noarch" "stork"}
                 #{"shark" "0.1-1.noarch"}}}}
            
        {:first "Library" :fpackages ["Nature" "Weird" "Many"]
              :second "simple-env" :spackages ["Nature"]}]]))

(defn verify-errata [type expected-errata]
  (org/switch test-org-errata)  
  (assert/is (= (if (= expected-errata {}) ; I don't need to add prefix if result is empty
                    {}
                    {"Com Errata Enterprise" {"ErrataZoo" expected-errata}} )
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

(defgroup content-search-errata
  :group-setup (fn []
                 (def ^:dynamic test-org-errata (uniqueify "erratasearch"))
                 (api/create-organization test-org-errata)
                 (fake/prepare-org-custom-provider test-org-errata fake/custom-errata-test-provider)
                 (env/create (uniqueify "simple-env") {:org-name test-org-errata :prior-env "Library"}))

  
   
  (deftest "Content Browser: Errata information"
    (org/switch test-org-errata)  
    (content-search/get-errata-set "*")
    (content-search/test-errata-popup-click "RHEA-2012:1011")
    (content-search/add-repositories ["ErrataZoo"])
    (content-search/click-repo-errata "ErrataZoo")
    (content-search/test-errata-popup-click "RHEA-2012:1011")
    (content-search/compare-repositories ["ErrataZoo"])
    (content-search/select-type :errata)
    (content-search/test-errata-popup-click "RHEA-2012:1011"))

  (deftests-errata-search
    {"UI - Search Errata in Content Search by exact Errata"
     [["\"RHEA-2012:1011\"" "RHEA-2012:1011"]
      ["\"RHEA-2012:1012\"" "RHEA-2012:1012"]
      ["\"RHEA-2012:1013\"" "RHEA-2012:1013"]]

     "UI - Search Errata in Content Search by exact title"
     [["title:\"Bear_Erratum\"""RHEA-2012:1010"]
      ["title:\"Sea_Erratum\"" "RHEA-2012:1011"]
      ["title:\"Bird_Erratum\"" "RHEA-2012:1012"]
      ["title:\"Gorilla_Erratum\"" "RHEA-2012:1013"]]
  
     "UI - Search Errata in Content Search by title regexp"
     [["title:Bear_*" "RHEA-2012:1010"]
      ["title:Sea*" "RHEA-2012:1011"]
      ["title:Bir*" "RHEA-2012:1012"]
      ["title:G*" "RHEA-2012:1013"]
      ["title:*i*" #{"RHEA-2012:1012" "RHEA-2012:1013"}]]
  
     "UI - Search Errata in Content Search by type regexp"
     [["type:secur*" #{"RHEA-2012:1011" "RHEA-2012:1012"}]
      ["type:*ug*" "RHEA-2012:1013"]
      ["type:*ement" "RHEA-2012:1010"]
      ["type:ttt" {}]
      ["type:" {}]]
  
     "UI - Search Errata in Content Search by type"
     [["type:security" #{"RHEA-2012:1011" "RHEA-2012:1012"}]
      ["type:bugfix" "RHEA-2012:1013"]
      ["type:enhancement" "RHEA-2012:1010"]]
  
     "UI - Search Errata in Content Search by severity"
     [["severity:low" "RHEA-2012:1010"]
      ["severity:important" "RHEA-2012:1011"]
      ["severity:critical" "RHEA-2012:1012"]
      ["severity:moderate" "RHEA-2012:1013"]
      ["severity:l*" "RHEA-2012:1010"]
      ["severity:*rtant" "RHEA-2012:1011"]
      ["severity:*cal" "RHEA-2012:1012"]
      ["severity:mod*" "RHEA-2012:1013"]
      ["severity:ttt" {}]
      ["severity:" {}]]}))




(defgroup content-search-tests-ensure-env
  :group-setup (fn []
                 (def ^:dynamic test-org (uniqueify "contentsearch"))
                 (api/create-organization test-org)
                 (fake/prepare-org test-org (mapcat :repos fake/some-product-repos)))
  
  
  (deftest "Search for content"
    :data-driven true
    :blockers (open-bz-bugs "855945")

    (fn [search-params pred]
      (let [search-res (with-org test-org
                         (org/switch)
                         (apply search-for-content search-params))]
        (assert/is (pred search-res))))


    ;;some simple search tests for *all* the entities of a given type
    [[[:repo-type] (fn [results] (= (set (repo-names results))
                                    (set (mapcat :repos fake/some-product-repos))))]
     [[:prod-type] (fn [results] (= (set (product-names results))
                                   (set (map :name fake/some-product-repos))))]
     [[:errata-type] (fn [results] (let [all-errata (errata-names results)]
                                    (and (= (set all-errata)
                                            fake/errata)
                                         (= (count all-errata)
                                            (* (count fake/errata)
                                               (count (repo-names results)))))))]])
  
  
  (deftest "Ensure for Library Env, Content Search"
    :data-driven true

    (fn [envz search-params pred & [paral-env]]
      (with-unique [test-org1 "redhat-org"]
        (fake/setup-org test-org1 envz)
        (if (not (nil? paral-env)) (env/create-path test-org1 (take 3 (unique-names "env3"))))
        (let [search-res (with-org test-org1
                           (org/switch)                       
                           (apply (->> (content-search/search-for-content)
                                       (content-search/validate-content-search-results)) [search-params {:envs envz}]))]
          (assert/is (pred search-res))
          (assert/is (-> search-res envs first (= "Library"))))))
    
              
    ;;setup different org & env scenarios to ensure that Library is the First env and always visible 
     [[(take 3 (unique-names "env1")) :prod-type (fn [results] (= (set (product-names results))
                                   (set (map :name fake/some-product-repos))))]
     [(take 1 (unique-names "env2")) :prod-type (fn [results] (= (set (product-names results))
                                   (set (map :name fake/some-product-repos))))]
     [(take 3 (unique-names "env3")) :prod-type (fn [results] (= (set (product-names results))
                                   (set (map :name fake/some-product-repos)))) ["parallel-env"]]
     (with-meta
     [ nil :prod-type (fn [results] (= (set (product-names results))
                                   (set (map :name fake/some-product-repos))))]
     {:blockers (open-bz-bugs "855945")})]))
   
    
  (defgroup content-search-tests
    content-search-repo-compare
    content-search-errata)
