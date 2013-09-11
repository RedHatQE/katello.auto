(ns katello.tests.notices
   (:require (katello  [notices :as notices]
                       [blockers :refer [bz-bugs]])
              [katello :as kt]
              [test.assert :as assert]
              [test.tree.script :refer [defgroup deftest]]
              [clojure.set :refer [join intersection difference union project select]]))

(defn level-assert [level list]
  (let [levels (->> list (map :level) (into #{}))]
                        (and (= 1 (count levels))
                             (= level (first levels)))
                        ))

(defgroup notices 
  (deftest "Automate: User Notifications Sorting"
    :data-driven true
    (fn [type]
      (notices/home)
      (notices/sort-by type)
      (let [notice-list (->> (notices/notices-list) 
                        (map type) 
                        ((fn [a] (if (= type :created)
                             (map  #(.parse notices/plan-dateformat %) a)
                             a))))]
        (assert/is (= notice-list (sort notice-list)))))
    
    [[:created] [:level]])
 
  (deftest "Automate: User Notifications - Search Filter"
    :data-driven true
    (fn [string assert-fn]
      (notices/home)
      (notices/search string)
      (assert/is (assert-fn (into #{} (notices/notices-list)))))
    
    [["level:error" (partial level-assert "error")]
     ["level:success" (partial level-assert "success")]
     ["level:message" (partial level-assert "message")]])
  )
