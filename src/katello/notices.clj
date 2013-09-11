(ns katello.notices
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]] 
            [katello :as kt]
            [clojure.zip :as zip]
            (katello [navigation  :refer [browser-fn] :as nav]
                     [notifications :as notification] 
                     [content-search :as cs]
                     [ui-common :as common]
                     [ui :as ui]))
  (:import  [java.text SimpleDateFormat]))

;; Locators

(ui/defelements :katello.deployment/any []
  {::sort-created             "//a[contains(.,'Created (UTC)')]"
   ::sort-level               "//a[contains(.,'Level')]"
   ::search-input             "//input[@id='search']"
   ::search-btn               "//button[@form='search_form']"})

;; Nav

(nav/defpages :katello.deployment/any katello.menu)

(def plan-dateformat (SimpleDateFormat. "E, dd MMM yyyy HH:mm:ss Z"))

(defn notices-list []
"Goes to notices page and returns table of notifications"
  (->> "notification_list"
       cs/get-string-of-html-element
       cs/get-zip-of-html-string
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
      (into [])))

(defn home []
   (nav/go-to ::page))
  
(defn page-content []
  "Goes to notices page and returns table of notifications"
  ;make it a set
      (nav/go-to ::page)
      (into #{} (notices-list)))

(defn sort-by [what]
  (let [sort-btn {:created ::sort-created
                  :level ::sort-level}]
  (browser click (sort-btn what)) (browser sleep 1000)))

(defn search [text]
  (browser setText ::search-input text)
  (browser click ::search-btn)
  (browser sleep 1000))

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
