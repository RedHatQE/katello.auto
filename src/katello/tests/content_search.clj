(ns katello.tests.content-search
  (:require (katello [tasks         :refer :all]
                     [ui-tasks      :refer :all]
                     [organizations :as org]
                     [environments  :as env]
                     [manifest      :as manifest]
                     [conf          :refer [config with-org]]
                     [api-tasks     :as api]
                     [fake-content  :as fake])
            [tools.verify :refer [verify-that]]
            [bugzilla.checker :refer [open-bz-bugs]]
            [test.tree.script :refer [defgroup deftest]]
            [clojure.set :refer [intersection difference union]]))

(declare test-org)

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

(defn setup-org [test-org envs]
      (api/create-organization test-org)
      (fake/prepare-org test-org (mapcat :repos fake/some-product-repos))
      (if (not (nil? envs)) (env/create-path test-org envs)))

(defn envs [results]
  (->> results :columns (map (comp :content :to_display))))

(defgroup content-search-repo-compare
  :group-setup (fn []
                 (def ^:dynamic test-org (uniqueify "contentsearch"))
                 (api/create-organization test-org)
                 (fake/prepare-org-custom-provider test-org fake/custom-providers)
                 (env/create (uniqueify "simple-env") {:org-name test-org :prior-env "Library"}))
  
  (deftest "Repo compare: Differences between repos can be qualified"
    :data-driven true
    
    (fn [first second packages-in-both packages-only-in-first packages-only-in-second]
      (compare-repositories [first second])
      (doseq [package packages-in-both]
        (verify-that (package-in-repository? package first))
        (verify-that (package-in-repository? package second)))
      (doseq [package packages-only-in-first]
        (verify-that (package-in-repository? package first))
        (verify-that (not (package-in-repository? package second))))
      (doseq [package packages-only-in-second]
        (verify-that (not(package-in-repository? package first)))
        (verify-that (package-in-repository? package second))))
   
    [["CompareZoo1" "CompareZoo2" [] 
                                  ["cheetah0.3-0.8.noarch" "elephant0.3-0.8.noarch"] 
                                  ["bear4.1-1.noarch" "camel0.1-1.noarch" "cat1.0-1.noarch"]]])
  
  (deftest "Repo compare: Differences between repos can be qualified"
    :data-driven true
    
    (fn [first second packages-in-both packages-only-in-first packages-only-in-second]
      (compare-repositories [first second])
      (doseq [package packages-in-both]
        (verify-that (package-in-repository? package first))
        (verify-that (package-in-repository? package second)))
      (doseq [package packages-only-in-first]
        (verify-that (package-in-repository? package first))
        (verify-that (not (package-in-repository? package second))))
      (doseq [package packages-only-in-second]
        (verify-that (not(package-in-repository? package first)))
        (verify-that (package-in-repository? package second))))
   
    [["CompareZoo1" "CompareZoo2" [] 
                                  ["cheetah0.3-0.8.noarch" "elephant0.3-0.8.noarch"] 
                                  ["bear4.1-1.noarch" "camel0.1-1.noarch" "cat1.0-1.noarch"]]])
  
  
    (deftest "Repo compare: Add and remove repos to compare"
      :data-driven true
      (fn [to-add to-remove]
        (compare-repositories to-add)
        (remove-repositories to-remove)
        (let [expected (difference (set to-add) (set to-remove))
              result   (set (get-search-result-repositories))]
          (verify-that (= expected result))))
            
        [[["CompareZoo1" "CompareZoo2"] ["CompareZoo1"]]])
  
  
    (deftest "Repo compare: Add many repos to compare"
      (let [repos (difference (fake/get-all-custom-repos) (fake/get-i18n-repos))]
        (verify-that (= repos
                        (set (compare-repositories (into [] repos)))))))
  
    (deftest "Repo compare: repos render correctly when internationalized"
      (let [expected (fake/get-i18n-repos)
            result (set (compare-repositories (into [] expected)))]
        (verify-that (= expected result)))))

(defgroup content-search-tests
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
        (verify-that (pred search-res))))


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
        (setup-org test-org1 envz)
        (if (not (nil? paral-env)) (env/create-path test-org1 (take 3 (unique-names "env3"))))
        (let [search-res (with-org test-org1
                           (org/switch)                       
                           (apply search-for-content [search-params {:envs envz}]))]
          (verify-that (pred search-res))
          (verify-that (-> search-res envs first (= "Library"))))))
    
              
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

