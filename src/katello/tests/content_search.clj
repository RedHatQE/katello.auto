(ns katello.tests.content-search
  (:require (katello [tasks         :refer :all]
                     [ui-tasks      :refer :all]
                     [organizations :as org] 
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


(defgroup content-search-tests
  :group-setup (fn []
                 (def ^:dynamic test-org (uniqueify "contentsearch"))
                 (api/with-admin
                   (api/create-organization test-org)
                   (fake/prepare-org test-org (mapcat :repos fake/some-product-repos))))
  
  (deftest "Search for content"
    :data-driven true

    (fn [search-params pred]
      (let [search-res (org/execute-with test-org
                         (apply search-for-content search-params))]
        (verify-that (pred search-res))))


    ;;some simple search tests for *all* the entities of a given type
    [[[[:repo-type] (fn [results] (= (set (repo-names results))
                                    (set (mapcat :repos fake/some-product-repos))))]]
     [[:prod-type] (fn [results] (= (set (product-names results))
                                   (set (map :name fake/some-product-repos))))]
     [[:errata-type] (fn [results] (let [all-errata (errata-names results)]
                                    (and (= (set all-errata)
                                            fake/errata)
                                         (= (count all-errata)
                                            (* (count fake/errata)
                                               (count (repo-names results)))))))]]))


