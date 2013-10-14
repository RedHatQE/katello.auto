(ns katello.tests.content-search
  (:require (katello [tasks :refer :all]
                     [content-search :as content-search]
                     [organizations :as org]
                     [providers :as providers]
                     [environments :as env]
                     [conf :refer [config]]
                     [changesets :refer [promote-delete-content]]
                     [rest :as rest]
                     [ui :as ui]
                     [blockers :refer [bz-bugs]]
                     [fake-content :as fake]
            )
            [katello.content-view-definitions :refer [rest-promote-published-content-view]]
            [katello :as kt]
            [test.assert :as assert]
            [test.tree.script :refer [defgroup deftest]]
            [clojure.set :refer [intersection difference union project select]]))

(declare test-org)
(declare test-org-compare)
(declare test-org-errata)

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

(defn compare-repositories [repos & {:keys [type,view]}]
      (content-search/go-to-content-search-page test-org-compare)
      (content-search/compare-repositories repos)
      (when type
        (content-search/select-type type))
      (when view
        (content-search/select-view view))
      (content-search/get-package-desc))

(defgroup content-search-repo-compare
  :group-setup (fn []
                 (def ^:dynamic test-org-compare (uniqueify (kt/newOrganization {:name "comparesearch"})))
                 (rest/create test-org-compare)
                 (org/switch test-org-compare)
                 (fake/prepare-org-custom-provider test-org-compare fake/custom-providers)
                 (rest/create (kt/newEnvironment {:name (uniqueify "simple-env") :org test-org-compare :prior "Library"})))
  
  (deftest "Repo compare: Differences between repos can be qualified"
    :uuid "597698ba-2b7c-8274-e523-bf3c3a85124c"
    :data-driven true
    
    (fn [type repo1 repo2 result]
      (assert/is
       (= (compare-repositories [repo1 repo2] :type type :view :all) result)))
    
    [[:packages "CompareZoo1" "CompareZoo2"
      {["mouse" "0.1.12-1.noarch"] [false true],
["cheetah" "1.25.3-5.noarch"] [false true],
["whale" "0.2-1.noarch"] [false true],
["horse" "0.22-2.noarch"] [false true],
["gorilla" "0.62-1.noarch"] [false true],
["dolphin" "3.10.232-1.noarch"] [false true],
["cockateel" "3.1-1.noarch"] [false true],
["lion" "0.3-0.8.noarch"] [true false],
["zebra" "0.1-2.noarch"] [false true],
["shark" "0.1-1.noarch"] [false true],
["frog" "0.1-1.noarch"] [false true],
["squirrel" "0.3-0.8.noarch"] [true false],
["dog" "4.23-1.noarch"] [false true],
["tiger" "1.0-4.noarch"] [false true],
["kangaroo" "0.2-1.noarch"] [false true],
["giraffe" "0.67-2.noarch"] [false true],
["cheetah" "0.3-0.8.noarch"] [true false],
["wolf" "9.4-2.noarch"] [false true],
["giraffe" "0.3-0.8.noarch"] [true false],
["lion" "0.4-1.noarch"] [false true],
["duck" "0.6-1.noarch"] [false true],
["crow" "0.8-1.noarch"] [false true],
["monkey" "0.3-0.8.noarch"] [true false],
["trout" "0.12-1.noarch"] [false true],
["elephant" "0.3-0.8.noarch"] [true false],
["elephant" "8.3-1.noarch"] [false true],
["squirrel" "0.1-1.noarch"] [false true],
["walrus" "0.3-0.8.noarch"] [true false],
["bear" "4.1-1.noarch"] [false true],
["penguin" "0.3-0.8.noarch"] [true false],
["penguin" "0.9.1-1.noarch"] [false true],
["pike" "2.2-1.noarch"] [false true],
["camel" "0.1-1.noarch"] [false true],
["cat" "1.0-1.noarch"] [false true],
["stork" "0.12-2.noarch"] [false true],
["walrus" "5.21-1.noarch"] [false true],
["walrus" "0.71-1.noarch"] [false true],
["fox" "1.1-2.noarch"] [false true],
["cow" "2.2-3.noarch"] [false true],
["chimpanzee" "0.21-1.noarch"] [false true]}]
     [:errata "CompareZoo1" "CompareZoo2"
      {"RHEA-2012:0004" [false true],
"RHEA-2012:0003" [false true],
"RHEA-2012:0002" [false true],
"RHEA-2012:0001" [false true],
"RHEA-2010:9984" [true false],
"RHEA-2010:9983" [true false]}]])
  
  (deftest "\"Compare\" UI - (SMOKE) Compare works for packages and errata"
    :uuid "96d8196d-68a2-c8b4-bf2b-9f7325056936"
    :data-driven true
    
     (fn [type repo1 repo2 result]
      (assert/is
       (= (compare-repositories [repo1 repo2] :type type :view :all) result)))
   
    [[:errata "CompareZoo1" "CompareZoo2"
      {"RHEA-2012:0004" [false true],
"RHEA-2012:0003" [false true],
"RHEA-2012:0002" [false true],
"RHEA-2012:0001" [false true],
"RHEA-2010:9984" [true false],
"RHEA-2010:9983" [true false]}]])
  
  (deftest "Repo compare: Comparison against empty repository"
    :uuid "6b84c9e0-2832-aeb4-847b-1b643c00cfec"
    (assert/is
       (every? #(= % [true false])
         (vals (compare-repositories ["CompareZoo1" "CompareZooNosync"] :type :packages :view :all)))))
  
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
      (content-search/expand-everything)
      (assert/is (content-search/click-if-compare-button-is-disabled?))
      (content-search/check-repositories repositories)
      (assert/is (not (content-search/click-if-compare-button-is-disabled?)))))
  
  
  (deftest "\"Compare\" UI - Selecting repos for compare"
    :uuid "27a2954d-a0cd-7544-479b-d495df6869c6"
    (content-search/go-to-content-search-page test-org-compare)
    (let [repositories ["CompareZoo1" "CompareZoo2"]]
      (content-search/add-repositories (fake/get-all-custom-repo-names))
      (content-search/expand-everything)
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

  
  (deftest "Content Browser: Repositories grouped by product"
    (content-search/go-to-content-search-page test-org-compare)
    (content-search/select-content-type :repo-type)
    (content-search/submit-browse-button)
    (assert/is
        (= (content-search/get-grid-row-headers)
           {"Default Organization View"
            #{{"Weird Enterprise" #{"China" "Russia"}}
              {"Com Nature Enterprise"
               #{"CompareZooNosync" "ManyRepositoryA" "CompareZoo1" "CompareZoo2"}}
              {"ManyRepository Enterprise"
               #{"ManyRepositoryA" "ManyRepositoryB" "ManyRepositoryC" "ManyRepositoryD" "ManyRepositoryE"}}}})))
  
  (deftest "Content Search: search repo info"
    :uuid "05955468-e85c-1f74-8c9b-a1fd152cfb70"
    :data-driven true
    (fn [repo-search expected-repos]
      (content-search/go-to-content-search-page test-org-compare)
      (assert/is (= expected-repos (content-search/search-for-repositories repo-search))))
    
    [["Co*" {"Default Organization View"
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
  `(concat ~(vec (for [
                      ; {:keys [name uuid data]}
                      test tests]
                   `(deftest ~(test :name)
                      :uuid ~(test :uuid)
                      :data-driven true
                      :blockers ~(test :blockers)
                      verify-errata
                      ~(test :data))))))


(def errata-search-table
  (let [[header & table] [[:errata :title :type :severity]
                          ["RHEA-2012:2010" "Squirrel_Erratum" "enhancement" "low"]
                          ["RHEA-2012:2011" "Camel_Erratum" "security" "important"]
                          ["RHEA-2012:2012" "Dog_Erratum" "security" "critical"]
                          ["RHEA-2012:2013" "Cow_Erratum" "bugfix" "moderate"]]]
  (map (partial zipmap header) table)))

(defgroup content-search-errata
  :group-setup (fn []
                 (def ^:dynamic test-org-errata (uniqueify (kt/newOrganization {:name"erratasearch"})))
                 (rest/create test-org-errata)
                 (fake/prepare-org-custom-provider test-org-errata fake/custom-errata-test-provider)
                 (rest/create (kt/newEnvironment {:name (uniqueify "simple-env") :org test-org-errata :prior-env "Library"}))
                 (org/switch test-org-errata))
  
  #_(deftest "Content Browser: Errata information"
    :uuid "f2a008f7-3219-1934-0c1b-82f47633be1c"
    (content-search/go-to-content-search-page test-org-errata)
    (content-search/get-errata-set "*")
    (content-search/test-errata-popup-click "RHEA-2012:2011")
    (content-search/add-repositories ["ErrataZoo"])
    (content-search/click-repo-errata "ErrataZoo")
    (content-search/test-errata-popup-click "RHEA-2012:2011")
    (content-search/compare-repositories ["ErrataZoo"])
   ; (content-search/select-type :errata)
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
     :blockers (bz-bugs "994917")
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

(declare test-org-env)
(declare publish-dev)
(declare publish-qa)

(def env-dev "Development")
(def env-qa "QA")
(def env-release "Release")

(defn test-repo-errata-count [repo view env]
    (content-search/go-to-content-search-page test-org-env)
    (content-search/select-content-type :repo-type)
    (content-search/submit-browse-button)
    (content-search/select-environments [env-dev env-qa env-release])
    (content-search/expand-everything)
    (content-search/get-repo-errata-count repo view env))

(defn test-env-shared-unique [environments view]
    (content-search/go-to-content-search-page test-org-env)
    (content-search/select-content-type :repo-type)
    (content-search/submit-browse-button)
    (content-search/select-environments environments)
    (content-search/select-view view)
    (content-search/get-grid-row-headers))

(defn cs-envcomp-setup []
                 (def ^:dynamic test-org-env (uniqueify (kt/newOrganization {:name "env-org"})))
                 (rest/create test-org-env)
                 (org/switch test-org-env)
                 (fake/prepare-org-custom-provider test-org-env fake/custom-env-test-provider)
                 (let [env-dev-r (kt/newEnvironment {:name env-dev :org test-org-env :prior-env "Library"})
                       env-qa-r (kt/newEnvironment {:name env-qa :org test-org-env :prior-env env-dev})
                       env-release-r (kt/newEnvironment {:name env-release :org test-org-env :prior-env env-qa})]
                   (rest/create-all-recursive [env-dev-r env-qa-r env-release-r])
        (def ^:dynamic publish-dev (:published-name
                   (rest-promote-published-content-view
                     test-org-env
                     env-dev-r
                     (nth (fake/repo-list-from-tree fake/custom-env-test-provider test-org-env)
                          1))))
        (def ^:dynamic publish-qa (:published-name
                   (rest-promote-published-content-view
                     test-org-env
                     env-qa-r
                     (nth (fake/repo-list-from-tree fake/custom-env-test-provider test-org-env)
                          2))))))

(defgroup content-search-env-compare
  :group-setup cs-envcomp-setup

  (deftest "Content Browser: Shared content for selected environments"
    :blockers (bz-bugs "953149")
    :data-driven true

    (fn [environments result]
      (assert/is (= result (test-env-shared-unique environments :shared))))
    
    [(fn [] [[env-dev] #{{"Default Organization View"
                   #{"Com Errata Inc" "Weird Enterprise" "Com Errata Enterprise"}}
                  {publish-dev {"Com Errata Inc" "ErrataZoo2"}}
                  {publish-qa "Weird Enterprise"}}])
     
     (fn [] [[env-dev env-qa] #{{"Default Organization View"
                          #{"Com Errata Inc" "Weird Enterprise" "Com Errata Enterprise"}}
                         {publish-dev "Com Errata Inc"}
                         {publish-qa "Weird Enterprise"}}])
      
     (fn [] [[env-dev env-qa env-release] #{{"Default Organization View"
                                      #{"Com Errata Inc" "Weird Enterprise" "Com Errata Enterprise"}}
                                     {publish-dev "Com Errata Inc"}
                                     {publish-qa "Weird Enterprise"}}])])
  
  (deftest "Content Browser: Unique content for selected environments"
    :blockers (bz-bugs "953149")
    :data-driven true

    (fn [environments result]
      (assert/is (= result (test-env-shared-unique environments :unique))))
    
    [(fn [] [[env-dev]
      #{{"Default Organization View"
         #{{"Weird Enterprise" #{"China" "Russia"}}
           {"Com Errata Enterprise" "ErrataZoo"}
           {"Com Errata Inc" "ErrataZoo2"}}}
        {publish-dev "Com Errata Inc"}
        {publish-qa {"Weird Enterprise" #{"China" "Russia"}}}}])
     
     (fn [] [[env-dev env-qa]
      #{{"Default Organization View"
         #{{"Weird Enterprise" #{"China" "Russia"}}
           {"Com Errata Enterprise" "ErrataZoo"}
           {"Com Errata Inc" "ErrataZoo2"}}}
        {publish-qa {"Weird Enterprise" #{"China" "Russia"}}}
        {publish-dev {"Com Errata Inc" "ErrataZoo2"}}}])
     
     (fn [] [[env-dev env-qa env-release]
      #{{"Default Organization View"
         #{{"Weird Enterprise" #{"China" "Russia"}}
           {"Com Errata Enterprise" "ErrataZoo"} {"Com Errata Inc" "ErrataZoo2"}}}
        {publish-dev {"Com Errata Inc" "ErrataZoo2"}}
        {publish-qa {"Weird Enterprise" #{"China" "Russia"}}}}])])
 
(deftest "Content Browser: Environment selector for content browser"
        :blockers (bz-bugs "953149")
        :data-driven true
        
    (fn [environments]
      (content-search/go-to-content-search-page test-org-env)
      (content-search/select-content-type :repo-type)
      (content-search/submit-browse-button)
      (content-search/select-environments environments)
      (assert/is (= (content-search/get-table-headers) (into [] (cons "Library" environments)))))
  
     [[[env-dev env-qa env-release]]
      [[env-dev env-qa]]
      [[env-qa]]])


  (deftest "Content Browser - Hover over a synced repository should show the correct number of packages and errata"
    :blockers (bz-bugs "953149")
    (assert/is (= ["8" "2" "0"]
                  (test-repo-errata-count "China" "Default Organization View" "Library"))))

  (deftest "Content Browser - Validate hover-over shows correct package/errata count with links after promoting the repo from Library to next env"
    :blockers (bz-bugs "953149")
    (assert/is (= ["8" "2""0"]
                  (test-repo-errata-count "China" publish-qa "QA"))))

  (deftest "Content Browser - Validate hover-over correctly showing package/errata count for empty repo"
    :blockers (bz-bugs "953149")
    (assert/is (= ["0" "0""0"]
                  (test-repo-errata-count "ErrataZoo2" "Default Organization View" "Library"))))

  (deftest "Content Search: search package info"
        :blockers (bz-bugs "953149")
        :data-driven true
        
        (fn [repo env view result]
                (content-search/go-to-content-search-page test-org-env)
                (content-search/select-content-type :repo-type)
                (content-search/submit-browse-button)
                (content-search/select-environments [env-dev env-qa env-release])
                (content-search/click-repo-desc repo env view)
                (assert/is (content-search/get-package-desc) result))
        
        [["China" "Library" "Default Organization View"
                         {["walrus" "0.3-0.8.noarch"] "A dummy package of walrus",
                          ["squirrel" "0.3-0.8.noarch"] "A dummy package of squirrel",
                          ["penguin" "0.3-0.8.noarch"] "A dummy package of penguin",
                          ["monkey" "0.3-0.8.noarch"] "A dummy package of monkey",
                          ["lion" "0.3-0.8.noarch"] "A dummy package of lion",
                          ["giraffe" "0.3-0.8.noarch"] "A dummy package of giraffe",
                          ["elephant" "0.3-0.8.noarch"] "A dummy package of elephant",
                          ["cheetah" "0.3-0.8.noarch"] "A dummy package of cheetah"}]
              ["Russia" "QA" publish-qa

{["mouse" "0.1.12-1.noarch"] "A dummy package of mouse",
  ["cheetah" "1.25.3-5.noarch"] "A dummy package of cheetah",
  ["whale" "0.2-1.noarch"] "A dummy package of whale",
  ["horse" "0.22-2.noarch"] "A dummy package of horse",
  ["gorilla" "0.62-1.noarch"] "A dummy package of gorilla",
  ["dolphin" "3.10.232-1.noarch"] "A dummy package of dolphin",
  ["cockateel" "3.1-1.noarch"] "A dummy package of cockateel",
  ["zebra" "0.1-2.noarch"] "A dummy package of zebra",
  ["shark" "0.1-1.noarch"] "A dummy package of shark",
  ["frog" "0.1-1.noarch"] "A dummy package of frog",
  ["dog" "4.23-1.noarch"] "A dummy package of dog",
  ["tiger" "1.0-4.noarch"] "A dummy package of tiger",
  ["kangaroo" "0.2-1.noarch"] "A dummy package of kangaroo",
  ["giraffe" "0.67-2.noarch"] "A dummy package of giraffe",
  ["wolf" "9.4-2.noarch"] "A dummy package of wolf",
  ["lion" "0.4-1.noarch"] "A dummy package of lion",
  ["duck" "0.6-1.noarch"] "A dummy package of duck",
  ["crow" "0.8-1.noarch"] "A dummy package of crow",
  ["trout" "0.12-1.noarch"] "A dummy package of trout",
  ["elephant" "8.3-1.noarch"] "A dummy package of elephant",
  ["squirrel" "0.1-1.noarch"] "A dummy package of squirrel",
  ["bear" "4.1-1.noarch"] "A dummy package of bear",
  ["penguin" "0.9.1-1.noarch"] "A dummy package of penguin",
  ["pike" "2.2-1.noarch"] "A dummy package of pike",
  ["camel" "0.1-1.noarch"] "A dummy package of camel",
  ["cat" "1.0-1.noarch"] "A dummy package of cat",
  ["stork" "0.12-2.noarch"] "A dummy package of stork",
  ["walrus" "5.21-1.noarch"] "A dummy package of walrus",
  ["walrus" "0.71-1.noarch"] "A dummy package of walrus",
  ["fox" "1.1-2.noarch"] "A dummy package of fox",
  ["cow" "2.2-3.noarch"] "A dummy package of cow",
  ["chimpanzee" "0.21-1.noarch"] "A dummy package of chimpanzee"}]])

  (deftest "Content Search: search repo info"
    :blockers (bz-bugs "953149")
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
               [[true ["\nPackages (8)\n" "\nErrata (2)\n"]] false false false]] )))

(defgroup content-search-tests
    content-search-repo-compare
    content-search-errata
    content-search-env-compare)

