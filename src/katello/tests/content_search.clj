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
            [test.tree.script :refer [defgroup deftest]]))

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
                 (fake/prepare-org test-org (mapcat :repos fake/some-product-repos))
                 (env/create (uniqueify "simple-env") {:org-name test-org :prior-env "Library"}))
  
  (deftest "Differences between repos can be qualified"
    )
  )



(defgroup content-search-tests
  :group-setup (fn []
                 (def ^:dynamic test-org (uniqueify "contentsearch"))
                 (api/create-organization test-org)
                 (fake/prepare-org test-org (mapcat :repos fake/some-product-repos))
                 (env/create (uniqueify "simple-env") {:org-name test-org :prior-env "Library"}))
  
  
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

