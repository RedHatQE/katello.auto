(ns katello.tests.content-search
  (:require (katello [tasks         :refer :all]
                     [ui-tasks      :refer :all]
                     [organizations :as org]
                     [environments  :as env]
                     [manifest      :as manifest]
                     [conf          :refer [config]]
                     [api-tasks     :as api]
                     [fake-content  :as fake])
            [tools.verify :refer [verify-that]]
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
    (api/with-admin
      (api/create-organization test-org)
      (fake/prepare-org test-org (mapcat :repos fake/some-product-repos)))
      (env/create-path test-org envs))


(defgroup content-search-tests
  
  
  
  (deftest "Ensure for Library Env, Content Search"
    :data-driven true

    (fn [envs search-params pred & [paral-env]]
      (with-unique [test-org1 "redhat-org"]
        (if (not (nil? envs)) (setup-org test-org1 envs) 
                              (api/with-admin
                                (api/create-organization test-org1)
                                (fake/prepare-org test-org1 (mapcat :repos fake/some-product-repos))))
        (if (not (nil? paral-env))
          (do
            (env/create-path test-org1 (take 3 (unique-names "env3")))))
        (let [search-res (org/execute-with test-org1                       
                           (apply search-for-content search-params))]
          (verify-that (pred search-res)))))


    ;;ensure that Library is the First env and always visible
    [[(take 3 (unique-names "env1")) [:prod-type] (fn [results] (= (set (product-names results))
                                   (set (map :name fake/some-product-repos))))]
     [(take 1 (unique-names "env2")) [:prod-type] (fn [results] (= (set (product-names results))
                                   (set (map :name fake/some-product-repos))))]
     [(take 3 (unique-names "env3")) [:prod-type] (fn [results] (= (set (product-names results))
                                   (set (map :name fake/some-product-repos)))) ["parallel-env"]]
     [ nil [:prod-type] (fn [results] (= (set (product-names results))
                                   (set (map :name fake/some-product-repos))))]]))


