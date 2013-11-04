(ns katello.dashboard
   (:require [clj-webdriver.taxi :as browser] 
             [katello :as kt]
             [clojure.zip :as zip]
             [clojure.string :as string]
             (katello [navigation :as nav]
                      [notifications :as notification] 
                      [organizations :as org]
                      [content-search :as cs]
                      [ui-common :as common]
                      [ui :as ui])))

(defn go-top [] 
       (browser/click "//a[@href='dashboard']")
       (Thread/sleep 5000))

(defn get-table-of-html-element [id]
  (->>
      (cs/get-string-of-html-element id)  
      cs/get-zip-of-html-string
      (cs/node-content-as []) 
      cs/postwalk-trim))
 
(defn cleanup [table]
  (->> table
       cs/normalize-nodes
       (cs/remove-nil-and-empty vector? [])
              cs/normalize-nodes))

(defn to-searchable-table [headers table]
  (if (coll? (first table))
    (->> table
       (map (partial zipmap headers))
       (into #{}))
    #{(zipmap headers table)}))
         
(defn get-dashboard-notices []
    (->> (get-table-of-html-element "dashboard_notices")  
         (cs/postwalk-rm "  ")
         (cs/postwalk-rm "More >>")
         cleanup
         ((partial to-searchable-table [:created :short-desc]))))
         ;(map #(assoc % :short-desc (string/replace (% :short-desc) #"\.\.\." "")))))

(defn get-dashboard-sync []
    (->> (get-table-of-html-element "dashboard_sync")  
         (cs/postwalk-rm " ")
         cleanup
         ((partial to-searchable-table [:product :result :date]))))

(defn get-dashboard-views []
    (->> (get-table-of-html-element "dashboard_content_views")  
         cleanup
         ((partial to-searchable-table [:view :result :date]))))

(defn get-dashboard-promotions []
    (->> (get-table-of-html-element "dashboard_promotions")  
         cleanup
         ((partial to-searchable-table [:promotion :result :env]))))
         
