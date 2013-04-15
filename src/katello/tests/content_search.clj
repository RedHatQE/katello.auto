(ns katello.tests.content-search
  (:require (katello [tasks         :refer :all]
                     [content-search :as content-search]
                     [organizations :as org]
                     [environments  :as env]
                     [conf          :refer [config with-org]]
                     [changesets :refer [promote-delete-content]]
                     [rest     :as rest]
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
                 (env/create {:name (uniqueify "simple-env") :org test-org-compare :prior "Library"}))
  
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
  
  
  (deftest "Repo compare: Add and remove repos to compare"
    :data-driven true
    (fn [to-add to-remove result]
      (content-search/go-to-content-search-page test-org-compare)
      (content-search/add-repositories to-add)
      (content-search/remove-repositories to-remove)
      
      (assert/is (= (content-search/get-result-repos) result)))
    
    [[["CompareZoo1" "CompareZoo2"] ["CompareZoo1"] {"Com Nature Enterprise" "CompareZoo2"}]])
  
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
  
  (deftest "Repo compare: repos render correctly when internationalized"
    (content-search/go-to-content-search-page test-org-compare)
    (let [expected (set (fake/get-i18n-repo-names))
          result (set (content-search/compare-repositories expected))]
      (assert/is (= expected result))))    

  (deftest "Content Search: search repo info"
    :data-driven true
    (fn [repo-search expected-repos]
      (content-search/go-to-content-search-page test-org-compare)
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
      (content-search/go-to-content-search-page test-org-compare)
      (do
        (assert/is (= expected-pkgs (content-search/search-for-packages pkg-search)))))     
    [["bear-4.1-1.noarch" {"WeirdLocalsUsing 標準語 Enterprise" {"Гесер"  #{"4.1-1.noarch" "bear"}},
                           "Com Nature Enterprise" {"CompareZoo2" #{"4.1-1.noarch" "bear"}}}
      {:first "Library" :fpackages ["Nature" "Weird"]
       :second "simple-env" :spackages ["Nature"]}]
     
     ["s*" {"WeirdLocalsUsing 標準語 Enterprise" 
              {"Гесер" #{#{"0.1-1.noarch" "squirrel"} 
                         #{"0.12-2.noarch" "stork"} 
                         #{"shark" "0.1-1.noarch"}},
               "洪" #{"0.3-0.8.noarch" "squirrel"}},
            "ManyRepository Enterprise"
              {"ManyRepositoryA" #{"0.3-0.8.noarch" "squirrel"}, 
               "ManyRepositoryB" #{"0.3-0.8.noarch" "squirrel"}, 
               "ManyRepositoryC" #{"0.3-0.8.noarch" "squirrel"}, 
               "ManyRepositoryD" #{"0.3-0.8.noarch" "squirrel"}, 
               "ManyRepositoryE" #{"0.3-0.8.noarch" "squirrel"}}, 
             "Com Nature Enterprise" 
               {"ManyRepositoryA" #{"0.3-0.8.noarch" "squirrel"}, 
                "CompareZoo1" #{"0.3-0.8.noarch" "squirrel"}, 
                "CompareZoo2" #{#{"0.1-1.noarch" "squirrel"} 
                                #{"0.12-2.noarch" "stork"} 
                                #{"shark" "0.1-1.noarch"}}}}
      
      {:first "Library" :fpackages ["Nature" "Weird" "Many"]
       :second "simple-env" :spackages ["Nature"]}]]))

(defn verify-errata [type expected-errata]
  (content-search/go-to-content-search-page test-org-errata)
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
                 (env/create {:name (uniqueify "simple-env") :org test-org-errata :prior-env "Library"}))
  
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
      ["type:ttt" {}]
      ["type:" {}]]
     
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
      ["severity:ttt" {}]
      ["severity:" {}]]}))



(comment
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
                         (apply content-search/search-for-content search-params))]
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
)

(declare test-org-env)
(def env-dev "Development")
(def env-qa "QA")
(def env-release "Release")

(defn test-env-shared-unique [environments result view]
      (content-search/go-to-content-search-page test-org-env)
      (content-search/select-content-type :repo-type)
      (content-search/submit-browse-button)
      (content-search/select-environments environments)
      (content-search/select-view view)
      (= (content-search/get-result-repos)
         result))

(defgroup content-search-env-compare
  :group-setup (fn []
                 (def ^:dynamic test-org-env  (uniqueify  (kt/newOrganization {:name "env-org"})))
                 (rest/create  test-org-env)
                 (org/switch test-org-env)
                 (fake/prepare-org-custom-provider test-org-env fake/custom-env-test-provider)
                 (env/create {:name env-dev :org test-org-env :prior-env "Library"})
                 (env/create {:name env-qa :org test-org-env :prior-env env-dev})
                 (env/create {:name env-release :org test-org-env :prior-env env-qa})
                 (promote-delete-content "Library" env-dev false 
                                         {:products ["Com Errata Enterprise" "WeirdLocalsUsing 標準語 Enterprise"]})
                 (promote-delete-content env-dev env-qa false 
                                         {:products ["WeirdLocalsUsing 標準語 Enterprise"]}))
  
  (deftest "Content Browser: Shared content for selected environments"
    :data-driven true

    (fn [environments result]
      (assert/is (test-env-shared-unique environments result :shared)))
    
    [[[env-dev] {"WeirdLocalsUsing 標準語 Enterprise" #{"Гесер" "洪"}, "Com Errata Enterprise" "ErrataZoo"}]
     [[env-dev env-qa] {"WeirdLocalsUsing 標準語 Enterprise" #{"Гесер" "洪"}}]
     [[env-dev env-qa env-release] {}]])
  
  (deftest "Content Browser: Unique content for selected environments"
    :data-driven true

    (fn [environments result]
      (assert/is (test-env-shared-unique environments result :unique)))
    
    [[[env-dev] {"Com Errata Inc" "ErrataZoo"}]
     [[env-dev env-qa] {"Com Errata Inc" "ErrataZoo", "Com Errata Enterprise" "ErrataZoo"}]
     [[env-dev env-qa env-release] {"Com Errata Inc" "ErrataZoo", "WeirdLocalsUsing 標準語 Enterprise" #{"Гесер" "洪"}, "Com Errata Enterprise" "ErrataZoo"}]])
 
(deftest "Content Browser: Environment selector for content browser"
        :data-driven true
        
    (fn [environments]
      (content-search/go-to-content-search-page test-org-env)
      (content-search/select-content-type :repo-type)
      (content-search/submit-browse-button)
      (content-search/select-environments environments)
      (assert/is  (= (content-search/get-table-headers) (into [] (cons "Library" environments)))))
  
     [[[env-dev env-qa env-release]]
      [[env-dev env-qa]]
      [[env-qa]]])

  (deftest "Content Search: search package info"
    :data-driven true
    
    (fn [repo env result]
      (content-search/go-to-content-search-page test-org-env)
      (content-search/select-content-type :repo-type)
      (content-search/submit-browse-button)
      (content-search/select-environments [env-dev env-qa env-release])
      (content-search/click-repo-desc repo env)
      (assert/is (content-search/get-package-desc) result))
    
    [["洪" "Library" 
               {["walrus" "0.3-0.8.noarch"] "A dummy package of walrus", 
                ["squirrel" "0.3-0.8.noarch"] "A dummy package of squirrel", 
                ["penguin" "0.3-0.8.noarch"] "A dummy package of penguin", 
                ["monkey" "0.3-0.8.noarch"] "A dummy package of monkey", 
                ["lion" "0.3-0.8.noarch"] "A dummy package of lion", 
                ["giraffe" "0.3-0.8.noarch"] "A dummy package of giraffe", 
                ["elephant" "0.3-0.8.noarch"] "A dummy package of elephant", 
                ["cheetah" "0.3-0.8.noarch"] "A dummy package of cheetah"}]
     ["Гесер" "QA" 
               {["mouse" "0.1.12-1.noarch"] "A dummy package of mouse", 
                ["cheetah" "1.25.3-5.noarch"] "A dummy package of cheetah", 
                ["horse" "0.22-2.noarch"] "A dummy package of horse", 
                ["gorilla" "0.62-1.noarch"] "A dummy package of gorilla", 
                ["dolphin" "3.10.232-1.noarch"] "A dummy package of dolphin", 
                ["cockateel" "3.1-1.noarch"] "A dummy package of cockateel", 
                ["shark" "0.1-1.noarch"] "A dummy package of shark", 
                ["frog" "0.1-1.noarch"] "A dummy package of frog", 
                ["dog" "4.23-1.noarch"] "A dummy package of dog", 
                ["kangaroo" "0.2-1.noarch"] "A dummy package of kangaroo", 
                ["giraffe" "0.67-2.noarch"] "A dummy package of giraffe", 
                ["lion" "0.4-1.noarch"] "A dummy package of lion", 
                ["duck" "0.6-1.noarch"] "A dummy package of duck", 
                ["crow" "0.8-1.noarch"] "A dummy package of crow", 
                ["elephant" "8.3-1.noarch"] "A dummy package of elephant", 
                ["squirrel" "0.1-1.noarch"] "A dummy package of squirrel", 
                ["bear" "4.1-1.noarch"] "A dummy package of bear", 
                ["penguin" "0.9.1-1.noarch"] "A dummy package of penguin", 
                ["pike" "2.2-1.noarch"] "A dummy package of pike", 
                ["camel" "0.1-1.noarch"] "A dummy package of camel", 
                ["cat" "1.0-1.noarch"] "A dummy package of cat", 
                ["stork" "0.12-2.noarch"] "A dummy package of stork", 
                ["fox" "1.1-2.noarch"] "A dummy package of fox", 
                ["cow" "2.2-3.noarch"] "A dummy package of cow", 
                ["chimpanzee" "0.21-1.noarch"] "A dummy package of chimpanzee"}]])

  (deftest "Content Search: search repo info"
    (content-search/go-to-content-search-page test-org-env)
    (content-search/select-content-type :repo-type)
    (content-search/submit-browse-button)
    (content-search/select-environments [env-dev env-qa env-release])
    (assert/is (content-search/get-repo-desc)
              [[[true "Packages (40)\nErrata (6)\n"] [true "Packages (40)\nErrata (6)\n"] [true "Packages (40)\nErrata (6)\n"] false]
               [[true ["\nPackages (8)\n" "\nErrata (2)\n"]] [true ["\nPackages (8)\n" "\nErrata (2)\n"]] [true ["\nPackages (8)\n" "\nErrata (2)\n"]] false]
               [[true ["\nPackages (32)\n" "\nErrata (4)\n"]] [true ["\nPackages (32)\n" "\nErrata (4)\n"]] [true ["\nPackages (32)\n" "\nErrata (4)\n"]] false] 
               [[true "Packages (8)\nErrata (2)\n"] [true "Packages (8)\nErrata (2)\n"] false false] 
               [[true ["\nPackages (8)\n" "\nErrata (2)\n"]] [true ["\nPackages (8)\n" "\nErrata (2)\n"]] false false] 
               [[true "Packages (8)\nErrata (2)\n"] false false false] 
               [[true ["\nPackages (8)\n" "\nErrata (2)\n"]] false false false]] ))


  (deftest "Content Browser: Repositories grouped by product"
    (content-search/go-to-content-search-page test-org-env)
    (content-search/select-content-type :repo-type)
    (content-search/submit-browse-button)
    (assert/is 
        (= (content-search/get-result-repos)
           {"Com Errata Inc" "ErrataZoo",
            "WeirdLocalsUsing 標準語 Enterprise" #{"Гесер" "洪"},
            "Com Errata Enterprise" "ErrataZoo"}))
    (assert/is  (= (content-search/get-table-headers) ["Library"]))
    (content-search/select-environments [env-dev env-qa env-release])
    (assert/is  (= (content-search/get-table-headers) ["Library" env-dev env-qa env-release]))))

(defgroup content-search-tests
  content-search-repo-compare
  content-search-errata
  content-search-env-compare
  )
