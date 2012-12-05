(ns katello.content-search
  (:require [clojure.data.json  :as json]
            [ui.navigate      :as nav]
            [clojure.xml :as xml]
            [clojure.zip :as zip]
            [clojure.data.zip :as zf]
            [clojure.data.zip.xml :as zfx]
            [com.redhat.qe.auto.selenium.selenium
             :refer [browser ->browser fill-form fill-item]]
            (katello [locators      :as locators]
                     [tasks         :refer :all]
                     [ui-tasks      :refer :all]
                     [notifications :as notification] 
                     [conf          :refer [config]] 
                     [api-tasks     :refer [when-katello when-headpin]]) 
            [slingshot.slingshot :refer [throw+ try+]]
            [test.assert         :as assert]
            [inflections.core    :refer [pluralize]] 
            (clojure [string     :refer [capitalize replace-first]] 
                     [set        :refer [union]]
                     [string     :as string]))
  (:import [com.thoughtworks.selenium SeleniumException]
           [java.text SimpleDateFormat]
           [java.io ByteArrayInputStream]))

(defn subtree-to-top  [ziptree]
  (if (nil? (zip/up ziptree))
     0
     (inc (subtree-to-top (zip/up ziptree))))) 

(defn get-search-page-result-list-of-lists []
  (let [normalize (fn [list]
                    (if (= 1 (count list))
	                    (first list)
	                    list))]
   (-> (locators/get-zip-of-html-element "grid_row_headers")
       (locators/tree-edit 
          (fn [tree] (contains? (zip/node tree) :content))      
          (fn [node]
              (normalize (into [] (remove empty? (remove nil? (:content node))))))
          (fn [node]
              node))
   (zip/node))))

(defn get-search-page-result-map-of-maps-of-sets-of-sets [depth]
  (-> (get-search-page-result-list-of-lists)
     (zip/vector-zip)
     (locators/tree-edit 
          (fn [tree] (>= depth (subtree-to-top tree)))      
          (fn [node] 
             (if (coll? node)
               (apply hash-map node)
               node))
          (fn [node]
              (if (coll? node)
               (into #{} node)
               node)))
    (zip/node)))


(defn attr-loc [locator attribute]
  (str (.getLocator locator) "@" attribute))

(defn get-repo-compare-package-names [] 
  (doall (for [locator (locators/get-all-of-locator locators/content-search-package-name)]
    (browser getText locator))))

(defn get-result-packages [] 
  (doall (for [locator (locators/get-all-of-locator locators/content-search-result-item-n)]
    (browser getText locator))))

(defn get-repo-content-search [] 
  (doall (for [locator (locators/get-all-of-locator locators/content-search-repo-header-name)]
    (browser getText locator))))

(defn validate-content-search-results [results]
  (let [cols (:columns results)
        vis-col-count (->> cols (filter #(:shown %)) count) 
        rows (vals (:rows results))]
    (doseq [row rows]
      (assert (>= (count cols) (-> (:cells row) keys count))
              "Number of cells in row is greater than the number of columns")
      (doseq [child-id (:child_ids (:cells row))]
        (assert (= 1 (count (filter #(= (:id %) child-id) rows)))
                (str "Child ID not found: " child-id)))))
    results)


(defn autocomplete-adder-for-content-search [auto-comp-box add-button cont-item]
  (browser type auto-comp-box cont-item)
  ;(browser setText auto-comp-box cont-item)
  ;; typeKeys is necessary to trigger drop-down list
  (browser typeKeys auto-comp-box " ")
  (let [elem (locators/auto-complete-item cont-item)] 
    (->browser (waitForElement elem "2000")
               (mouseOver elem)
               (click elem)))
    (browser click add-button))

(defn get-search-result-repositories [] 
  (doall (for [locator (locators/get-all-of-locator locators/content-search-repo-column-name)]
    (browser getText locator))))

(defn row-in-column? [package repository]
  (let [row-id (browser getAttribute (attr-loc 
                                       (locators/search-result-row-id package)
                                       "data-id"))
       col-id (browser getAttribute (attr-loc 
                                       (locators/search-result-col-id repository)
                                       "data-id"))]
      (not (= "--" 
              (browser getText (locators/search-result-cell row-id col-id))))))

(defn package-in-repository? [package repository]
   (row-in-column? package repository))

(defn load-all-results []
  (while (browser isElementPresent :content-search-load-more)
    (browser click :content-search-load-more)))

(defn add-to-repository-browser [repository]
  (autocomplete-adder-for-content-search :repo-auto-complete :add-repo repository))

(defn remove-one-repository-from-browser [repository]
  (browser click (locators/content-search-repo-remove repository)))

(defn remove-repositories [repositories]
  (do
    (doseq [removing repositories]
      (remove-one-repository-from-browser removing))
    (browser click :browse-button)))

(defn get-repo-search-data-id-map [repositories]
  (apply hash-map 
    (reduce 
      (fn [result name] 
        (conj result  name
          (apply str (filter #(#{\0,\1,\2,\3,\4,\5,\6,\7,\8,\9} %) ; filter out non-numbers   
            (browser getAttribute (attr-loc 
                                  (locators/search-result-repo-id name)
                                  "data-id")))))) 
       []
       repositories)))

(defn check-repositories [repositories]
  (let [repo-id-map (get-repo-search-data-id-map repositories)]
    (doseq [repository repositories]
      (browser check (locators/content-search-compare-checkbox (str "repo_" (repo-id-map repository)))))))


(defn add-repositories [repositories]
  (navigate :content-search-page)
  (browser select :content-search-type "Repositories")
  (browser check :repo-auto-complete-radio)
  (doseq [repository repositories]
    (add-to-repository-browser repository))
  (browser click :browse-button))

(defn click-if-compare-button-is-disabled? []
  (browser click :repo-compare-button)
  (not (=  "" (browser getText :repo-compare-button))))

(defn click-repo-errata [repo]
  (let [repo-id ((get-repo-search-data-id-map [repo]) repo) ]
  (browser click (locators/search-result-repo-errata-link  repo-id))))

(defn compare-repositories [repositories]
  (add-repositories repositories)
  (check-repositories repositories)
  (browser click :repo-compare-button)
  (get-repo-content-search))

(defn select-type [type]
  (case type 
        :packages  (browser select :repo-result-type-select "Packages")
        :errata    (browser select :repo-result-type-select "Errata")))

(defn select-view [set-type]
  (case set-type 
        :all (browser select :repo-result-filter-select   "All")
        :shared (browser select :repo-result-filter-select   "Union")
        :unique (browser select :repo-result-filter-select   "Difference")))

(defn get-repo-packages [repo & {:keys [view] :or {view :packages} }] 
  (compare-repositories [repo])
  (select-type view)
  (load-all-results)
  (get-result-packages))

(defn search-for-repositories [repo]
  (navigate :content-search-page)
  (browser select :content-search-type "Repositories")
  (browser setText :repo-search repo)
  (browser click :browse-button)

  (load-all-results)
  (get-search-page-result-map-of-maps-of-sets-of-sets 0))

(defn search-for-packages [package]
  (navigate :content-search-page)
  (browser select :content-search-type "Packages")
  (browser setText :pkg-search package)
  (browser click :browse-button)
  (load-all-results)
  (get-search-page-result-map-of-maps-of-sets-of-sets 1))

(defn search-for-content
  "Performs a search for the specified content type (:prod-type, :repo-type,
   :pkg-type, :errata-type) using any product, repository, package or errata
   filters specified. Note that while prods and repos should be vectors, errata
   and pkgs are expected to be strings. A vector of environments corresponding
   to table columns may also be specified.  Returns the search results as raw
   data from the browser javascript. 
   Example: search-for-content :errata-type {:prods ['myprod']
                                             :repos ['myrepo']
                                             :errata 'myerrata'}"
  [content-type & [{:keys [envs prods repos pkg errata]}]]
  (assert (some #{content-type} [:prod-type :repo-type :pkg-type :errata-type])
          "Unknown content search type.")
  (case content-type 
    :prod-type   (assert (and (empty? repos) (empty? pkg) (empty? errata)))
    :repo-type   (assert (and (empty? pkg) (empty? errata)))
    :pkg-type    (assert (empty? errata))
    :errata-type (assert (empty? pkg)))

  ;; Navigate to content search page and select content type
  (let [ctype-map {:prod-type   "Products"
                   :repo-type   "Repositories"
                   :pkg-type    "Packages"
                   :errata-type "Errata"}
        ctype-str (ctype-map content-type)]
    (navigate :content-search-page)
    (browser select :content-search-type ctype-str))
  
  ;; Select environments (columns)
  (doseq [env envs]
    (let [col-locator (locators/content-search-column env)]
    (browser mouseOver :column-selector) 
    (browser mouseOver col-locator) 
    (browser click     col-locator)
    (browser mouseOut :column-selector)))

  ;; Add content filters using auto-complete
  (doseq [[auto-comp-box add-button cont-items] 
          [[:prod-auto-complete :add-prod prods] 
           [:repo-auto-complete :add-repo repos]]]
    (doseq [cont-item cont-items]
      (autocomplete-adder-for-content-search auto-comp-box add-button cont-item)))

  ;; Add package
  (when-not (empty? pkg) (browser setText :pkg-search pkg))

  ;; Add errata
  (when-not (empty? errata) (browser setText :errata-search errata))
  
  (browser click :browse-button)

  (load-all-results)
  
  ;;extract and return content
  (->> "JSON.stringify(window.KT.content_search_cache.get_data());"
       (browser getEval)
       (json/read-json)))

 (defn test-errata-popup-click [name]
   (browser click (locators/content-search-span-text name))
    (browser mouseOver  :errata-search)
   (assert/is (.contains (browser getText :details-container) name))
   (browser click (locators/content-search-span-text name))
   (assert/is (= 0 (browser getXpathCount :details-container))))

  (defn test-errata-popup-hover [name]
   (assert/is 
     (.contains
       (do
         (browser mouseOver (locators/content-search-span-text name))
         (browser waitForElement  :details-container "4000")          
         (browser getText :details-container))
       name))
   (browser mouseOut (.getLocator (locators/content-search-span-text name)))
   (browser sleep 1000)
   (assert/is (= 0 (browser getXpathCount :details-container))))

(defn get-errata-set  [type]
    (search-for-content :errata-type {:errata type})
    (get-search-page-result-map-of-maps-of-sets-of-sets 1))

(defn get-repo-set  [search-string]
       (search-for-repositories search-string)
       (get-search-page-result-map-of-maps-of-sets-of-sets 0))

(defn get-result-repos []
  (get-search-page-result-map-of-maps-of-sets-of-sets 0))