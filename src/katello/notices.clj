(ns katello.notices
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]] 
            [katello :as kt]
            [clojure.zip :as zip]
            (katello [navigation  :refer [browser-fn] :as nav]
                     [notifications :as notification] 
                     [content-search :as cs]
                     [ui-common :as common]
                     [ui :as ui])))
;; Nav

(nav/defpages :katello.deployment/any katello.menu)

(defn page-content []
  "Goes to notices page and returns table of notifications"
  (nav/go-to ::page)
  (->> "notification_list"
       cs/get-string-of-html-element
       cs/get-zip-of-xml-string
       zip/node    
      ;I want table to be formated as list of lists 
       (cs/node-content-as [])
       cs/postwalk-trim
      ;convert ["asdf"] to "asdf" and [] to ""
      (map (partial map 
             #(cond (= % []) ""
                    (= 1 (count %)) (first %)
                    :else %))) 
      ;add column names, so that table is searchable \w clojure.set
      (map 
        (partial zipmap [:created :level :org :desc]))
      ;make it a set
      (into #{})))

(defn from-action [action]
  (let [notifs-before (page-content)]
    (action)
    (clojure.set/difference (page-content) notifs-before)))

(defn column-contains [column what-list table]
  (reduce
    (fn [table what]
      (clojure.set/select #(.contains (column %) what) table))
    table
    what-list))

(def description-contains (partial column-contains :desc))

(def only-level (partial column-contains :level))
