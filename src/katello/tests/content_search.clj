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
  
  (defn repo-compare-test [type first first-packages second  second-packages]
        (let [only-in-first  (difference first-packages second-packages)
              only-in-second (difference second-packages first-packages)
              union-pkg      (union first-packages second-packages)
              intersect-pkg  (intersection first-packages second-packages)]
          (compare-repositories [first second])
          (show-select type)
          (view-select :all)
          (load-all-results)
          (doseq [package intersect-pkg]
             (verify-that (package-in-repository? package first))
             (verify-that (package-in-repository? package second)))
          (doseq [package only-in-first]
             (verify-that (package-in-repository? package first))
             (verify-that (not (package-in-repository? package second))))
          (doseq [package only-in-second]
             (verify-that (not(package-in-repository? package first)))
             (verify-that (package-in-repository? package second)))
          (view-select :shared)
          (load-all-results)
          (doseq [package intersect-pkg]
             (verify-that (package-in-repository? package first))
             (verify-that (package-in-repository? package second)))
          (view-select :unique)
          (load-all-results)
          (doseq [package only-in-second]
             (verify-that (not(package-in-repository? package first)))
             (verify-that (package-in-repository? package second)))
          (doseq [package only-in-first]
             (verify-that (package-in-repository? package first))
             (verify-that (not (package-in-repository? package second))))))
  
   (defn repo-all-shared-different-test [type first first-packages second  second-packages]
        (let [only-in-first  (difference first-packages second-packages)
              only-in-second (difference second-packages first-packages)
              union-pkg      (union first-packages second-packages)
              intersect-pkg  (intersection first-packages second-packages)]
          (compare-repositories [first second])
          (show-select type)
          (view-select :all)
          (load-all-results)
          (verify-that (= union-pkg
                        (into #{} (get-repo-compare-packages))))
          (view-select :shared)
          (load-all-results)
          (verify-that (= intersect-pkg
                        (into #{} (get-repo-compare-packages))))
          (view-select :unique)
          (load-all-results)
          (verify-that (= (difference union-pkg intersect-pkg)
                        (into #{} (get-repo-compare-packages))))))

(defgroup content-search-repo-compare
  :group-setup (fn []
                 (def ^:dynamic test-org (uniqueify "contentsearch"))
                 (api/create-organization test-org)
                 (fake/prepare-org-custom-provider test-org fake/custom-providers)
                 (env/create (uniqueify "simple-env") {:org-name test-org :prior-env "Library"}))
  
  (deftest "Repo compare: Differences between repos can be qualified"
      :data-driven true
      
      (fn [type repo1 repo2]
        (repo-compare-test  type repo1 (into #{} (get-repo-packages repo1 :view type))
                                      repo2 (into #{} (get-repo-packages repo2 :view type))))
      
      [[:packages "CompareZoo1" "CompareZoo2"]
       [:errata "CompareZoo1" "CompareZoo2"]])
  
  (deftest "\"Compare\" UI - (SMOKE) Compare works for packages and errata"
      :data-driven true
      
      (fn [type repo1 repo2]
        (repo-all-shared-different-test  type repo1 (into #{} (get-repo-packages repo1 :view type))
                                              repo2 (into #{} (get-repo-packages repo2 :view type))))
      
      [[:packages "CompareZoo1" "CompareZoo2"]
       [:errata "CompareZoo1" "CompareZoo2"]])
  
   (deftest "Repo compare: Comparison against empty repository"
      :data-driven true
      
      (fn [type repo1]
        (repo-compare-test  type repo1 (into #{} (get-repo-packages repo1 :view type))
                                 "CompareZooNosync" #{})
        (repo-all-shared-different-test type repo1 (into #{} (get-repo-packages repo1 :view type))
                                 "CompareZooNosync" #{}))
      
      [[:packages "CompareZoo1"]
       [:errata "CompareZoo1"]])
      
   
    (deftest "Repo compare: Add and remove repos to compare"
      :data-driven true
      (fn [to-add to-remove]
        (compare-repositories to-add)
        (remove-repositories to-remove)
        (let [expected (difference (set to-add) (set to-remove))
              result   (set (get-search-result-repositories))]
          (verify-that (= expected result))))
            
        [[["CompareZoo1" "CompareZoo2"] ["CompareZoo1"]]])
    
    (deftest "\"Compare\" UI - User cannot submit compare without adequate repos selected"
      (let [repositories ["CompareZoo1" "CompareZoo2"]]
        (add-repositories-to-search-page repositories)
        (verify-that (click-if-compare-button-is-disabled?))
        (compare-repositories-in-search-result repositories)
        (verify-that (not (click-if-compare-button-is-disabled?)))))
  
    (deftest "Repo compare: Add many repos to compare"
      (let [repos (difference (set (fake/get-all-custom-repos)) (set (fake/get-i18n-repos)))]
        (verify-that (= repos
                        (set (compare-repositories (into [] repos)))))))
  
    (deftest "Repo compare: repos render correctly when internationalized"
      (let [expected (fake/get-i18n-repos)
               result (compare-repositories expected)]
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

