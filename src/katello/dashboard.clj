(ns katello.dashboard
   (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]] 
             [katello :as kt]
             [clojure.zip :as zip]
             [clojure.string :as string]
             (katello [navigation  :refer [browser-fn] :as nav]
                      [notifications :as notification] 
                      [organizations :as org]
                      [content-search :as cs]
                      [ui-common :as common]
                      [ui :as ui])))

(defn go-top [] 
       (browser open "/katello")
       (browser sleep 5000))

(defn get-dashboard-notices []
    (->> (cs/get-string-of-body-element)  
         cs/get-zip-of-html-string
              (cs/search-in-zip
                 #(= (some-> % zip/node :attrs :data-url) "/katello/dashboard/notices" ))
              zip/node
              zip/xml-zip
              (cs/search-in-zip
                 #(= (some-> % zip/node :tag) :ul ))
              zip/node
              (cs/node-content-as []) 
              cs/postwalk-trim 
              (cs/postwalk-rm "  ")
              (cs/postwalk-rm "More >>")
              cs/normalize-nodes
              (cs/remove-nil-and-empty vector? [])
              (map (partial zipmap [:created :short-desc]))
              (map #(assoc % :short-desc (string/replace (% :short-desc) 
                                                  #"\.\.\." "")))
              (into #{})))

(defn get-dashboard-sync []
    (->> (cs/get-string-of-body-element)  
         cs/get-zip-of-html-string
         (cs/search-in-zip
            #(= (some-> % zip/node :attrs :data-url) "/katello/dashboard/sync" ))
         zip/node
         (cs/node-content-as []) 
         cs/postwalk-trim
         (cs/postwalk-rm " ")
         cs/normalize-nodes
         (cs/remove-nil-and-empty vector? [])
         cs/normalize-nodes
         (map #(if (< 2 (count %))
                 (zipmap [:product :result :date] %)
                 (zipmap [:product :result] %)))
         (into #{})))
             

(defn get-dashboard-views []
    (->> (cs/get-string-of-html-element "dashboard_content_views")  
         cs/get-zip-of-html-string
         (cs/node-content-as []) 
         cs/postwalk-trim
         (cs/remove-nil-and-empty vector? [])
         cs/normalize-nodes
         (map (partial zipmap [:view :result :date]))
         (into #{})))

(defn get-dashboard-promotions []
    (->> (cs/get-string-of-html-element "dashboard_promotions")  
         cs/get-zip-of-html-string
         (cs/node-content-as []) 
         cs/postwalk-trim
         (cs/remove-nil-and-empty vector? [])
         cs/normalize-nodes
         (map (partial zipmap [:promotion :result :env]))
         (into #{})))
               


