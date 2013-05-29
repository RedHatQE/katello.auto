(ns katello.content-search
  (:require [clojure.data.json  :as json]
            [clojure.xml :as xml]
            [clojure.zip :as zip]
            [clojure.data.zip :as zf]
            [clojure.data.zip.xml :as zfx]
            [clojure.string :refer [split trim]]
            [clojure.walk :refer [postwalk] ]
            [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            (katello [navigation :as nav]
                     [tasks         :refer :all]
                     [ui            :as ui]
                     [ui-common     :as common]
                     [notifications :as notification]
                     [conf          :refer [config]]
                     [rest     :refer [when-katello when-headpin]])
            [slingshot.slingshot :refer [throw+ try+]]
            [pl.danieljanus.tagsoup :refer [parse-xml]]
            [test.assert         :as assert]
            [inflections.core    :refer [pluralize]])
  (:import [com.thoughtworks.selenium SeleniumException]
           [org.ccil.cowan.tagsoup Parser]
           [java.io InputStream File FileInputStream ByteArrayInputStream BufferedInputStream InputStreamReader BufferedReader]
           [org.xml.sax InputSource]
           [java.text SimpleDateFormat]
           [java.io ByteArrayInputStream]))

;; Locators

(ui/defelements :katello.deployment/any []
  {::type-select               "//select[@id='content']"
   ::add-prod                  "add_product"
   ::add-repo                  "add_repo"
   ::repo-result-type-select   "//article[@id='maincontent']//article[@id='comparison_grid']//header//div[@id='left_select']//select"
   ::repo-result-filter-select "//div[@id='right_select']//select"
   ::row-headers               "//ul[@id='grid_row_headers']/li"
   ::col-headers               "//ul[@id='column_headers']/li"
   ::repo-auto-complete-radio  "repos_auto_complete_radio"
   ::prod-auto-complete        "product_auto_complete"
   ::repo-auto-complete        "repo_auto_complete"
   ::repo-search               "//input[@id='repo_search_input']"
   ::pkg-search                "//div[@id='package_search']/input[@id='search']"
   ::errata-search             "//div[@id='errata_search']//input[@id='search']"
   ::browse-button             "//input[@id='browse_button']"
   ::repo-compare-button       "//a[@id='compare_btn']"
   ::load-more                 "//a[contains(@class,'load_row_link')]"
   ::column-selector           "//div[@id='column_selector']/span[contains(@class,'path_button')]"
   ::details-container         "//div[contains(@class,'details_container')]"
   ::switcher-button           "//a[@id='switcherButton']"
   })

(sel/template-fns
 {auto-complete-item      "//ul[@role='listbox']//a[contains(.,'%s')]"
  result-item-n           "//ul[@id='grid_row_headers']/li[%s]"
  package-name            "//ul[@id='grid_row_headers']/li[%s]/span/span[1]"
  compare-checkbox        "//input[@type='checkbox' and @name='%s']"
  result-repo-errata-link "//div[@id='grid_row_%s']//a[@data-type='repo_errata' and @data-env_id='%s']"
  compare-checkbox-all    "//div[@id='grid_content']//input[%s]"
  repo-remove             "//div[@id='repo_autocomplete_list']/ul/li[@data-name='%s']/i[contains(@class,'remove')]"
  repo-header-name        "//ul[@id='column_headers']/li[%s]/span[3]"
  col-header-name         "//ul[@id='column_headers']/li[%s]"
  repo-column-name        "//ul[@id='grid_row_headers']//li[contains(@data-id,'repo')][%s]"
  column                  "//div/span[contains(@class,'checkbox_holder')]/input[@type='checkbox' and @data-node_name='%s']"
  span-text               "//article[@id='comparison_grid']//span[text()='%s']"
  result-repo-id          "//ul[@id='grid_row_headers']//ul[contains(@id,'child_header_list')]//li[contains(.,'%s')]"
  result-col-id           "//ul[@id='column_headers']//li[contains(.,'%s')]"
  result-row-id           "//ul[@id='grid_row_headers']//li[contains(.,'%s')]"
  result-cell             "//div[@id='grid_row_%s']/div[contains(@class,'cell_%s')]/i"
  repo-link               "//div[@id='grid_row_%s']//a[@data-type='repo_packages' and @data-env_id='%s']" })

;; Nav

(nav/defpages :katello.deployment/any katello.menu)

;; Tasks

(defn get-all-of-locator [locatorfn]
  "For locators that accept position and '*' as input, counts xpath-count and returns list of all aviable locators."
  (let [count (browser getXpathCount (locatorfn "'*'"))]
    (reduce (fn [accumulator number]
              (conj
               accumulator
               (locatorfn (str number))))
            []
            (range 1 (inc count)))))

(defn numeric-str? [num]
  (and (string? num)
       (= num (re-matches #"[0-9]+" num))))

(defn get-string-of-html-element [id]
  (->> id
     (format "window.document.getElementById('%s').innerHTML;")
     (browser getEval)
     (format "<root>%s</root>")))

(defn get-zip-of-xml-string [xml-string]
  (->> xml-string
     java.io.StringReader.
     org.xml.sax.InputSource.
     xml/parse
     zip/xml-zip))

(defn get-zip-of-html-string [html-string]
  (->> html-string
     .getBytes
     ByteArrayInputStream.
     parse-xml
     zip/xml-zip))

(defn node-content-as [empty-coll tree]
  (postwalk 
    #(cond 
       (and (map? %) (contains? % :content)) (into empty-coll (:content %))  
       :else %)
    tree))

(defn normalize-nodes [tree]
  (postwalk 
    #(if (and (not (map? %)) (= 1 (count %)))
                (first %)
                %)
    tree))

(defn remove-nil-and-empty [when? empty-col tree]
  (postwalk #(if (when? %)
             (->> %
                (remove nil?)
                (remove empty?)
                (into empty-col))
                %) 
            tree))

(defn get-search-page-result-list-of-lists [xml-zip]
  (->> xml-zip
    zip/node    
	  (node-content-as []) 
    (remove-nil-and-empty vector? []) 
    normalize-nodes
    (postwalk #(if (= "--" %) false %))))


(defn get-grid-row-headers []
  (->> "grid_row_headers"
       get-string-of-html-element
       get-zip-of-xml-string
       zip/node
(postwalk 
   #(cond 
     (vector? %) (reduce 
                   (fn [acc n]
                     (if (and (map? (last acc)) (map? n) (= :ul (:tag  n)))
                       (into (into [] (butlast acc)) [{(last acc) n}] )
                       (into acc [n])))
                   [] %)
     :else %))
   (node-content-as #{}) 
   (remove-nil-and-empty set? #{})
    normalize-nodes))

(defn get-search-page-result-list-of-lists-html [id]
     (-> id
       get-string-of-html-element
       get-zip-of-html-string
       get-search-page-result-list-of-lists))

(defn get-search-page-result-list-of-lists-xml [id]
     (-> id
       get-string-of-html-element
       get-zip-of-xml-string
       get-search-page-result-list-of-lists))

(def ^{:arglists '([locator attribute])}
  attr-loc (partial format  "%s@%s"))

(defn get-repo-compare-package-names []
  (doall (for [locator (get-all-of-locator package-name)]
           (browser getText locator))))

(defn get-result-packages []
  (doall (for [locator (get-all-of-locator result-item-n)]
           (browser getText locator))))

(defn get-table-headers []
  (doall (remove empty?
                 (for [locator (get-all-of-locator col-header-name)]
                   (browser getText locator)))))

(defn get-repo-content-search []
  (doall (for [locator (get-all-of-locator repo-header-name)]
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
  (let [elem (auto-complete-item cont-item)]
    (sel/->browser (type auto-comp-box cont-item)
                   (setText auto-comp-box cont-item)
                   ;; typeKeys is necessary to trigger drop-down list
                   (typeKeys auto-comp-box " ")
                   ;(waitForElement elem "2000")
                   ;(mouseOver elem)
                   ;(click elem)
                   (click add-button))))

(defn get-search-result-repositories []
  (doall (for [locator (get-all-of-locator repo-column-name)]
           (browser getText locator))))

(defn get-col-id [col]
  (browser getAttribute (attr-loc (result-col-id col) "data-id")))

(defn row-in-column? [package repository]
  (let [row-id (browser getAttribute (attr-loc
                                      (result-row-id package)
                                      "data-id"))
        col-id (get-col-id repository)]
    (not (= "--" 
            (browser getText (result-cell row-id col-id))))))

(defn package-in-repository? [package repository]
  (row-in-column? package repository))

(defn load-all-results []
  (while (browser isElementPresent ::load-more)
    (browser click ::load-more)))

(defn submit-browse-button []
  (browser click ::browse-button)
  (load-all-results))

(defn add-to-repository-browser [repository]
  (autocomplete-adder-for-content-search ::repo-auto-complete ::add-repo repository))

(defn remove-one-repository-from-browser [repository]
  (browser click (repo-remove repository)))

(defn remove-repositories [repositories]
  (do
    (doseq [removing repositories]
      (remove-one-repository-from-browser removing))
    (browser click ::browse-button)))

(defn name-map-to-name [name-map]
  (->> name-map
    (into [])
    flatten
    (clojure.string/join "_")))

(defn get-repo-search-data-name-map [repositories]
  (->> repositories
    (map (fn [name]
              (->> name
                result-repo-id
                (#(attr-loc % "data-id"))
                (browser getAttribute )
                (#(split % #"_"))
                (apply hash-map))) )          
    (zipmap repositories)))
    
(defn get-repo-search-library-id-map [repositories]
  (apply hash-map 
         (reduce 
          (fn [result name] 
            (conj result  name
                  (apply str (filter #(#{\0,\1,\2,\3,\4,\5,\6,\7,\8,\9} %) ; filter out non-numbers   
                                     (browser getAttribute (attr-loc 
                                                            (result-repo-id name)
                                                            "data-id")))))) 
          []
          repositories)))

;problem with two repos of a same name
(defn get-repo-search-data-name [repo-name]
  ((get-repo-search-data-name-map [repo-name]) repo-name))

(defn check-repositories [repositories]
  (let [repo-id-map (get-repo-search-data-name-map repositories)]
    (doseq [repository repositories]
      (browser check (compare-checkbox (name-map-to-name (repo-id-map repository)))))))

(defn go-to-content-search-page [org]
  (nav/go-to ::page org))

(defn add-repositories [repositories]
  (browser select ::type-select "Repositories")
  (browser check ::repo-auto-complete-radio)
  (doseq [repository repositories]
    (add-to-repository-browser repository))
  (browser click ::browse-button))

(defn click-if-compare-button-is-disabled? []
  (browser click ::repo-compare-button)
  (not (=  "" (browser getText ::repo-compare-button))))

(defn click-repo-errata [repo]
  (let [repo-id ((get-repo-search-data-name-map [repo]) repo) ]
    (browser click (result-repo-errata-link  (name-map-to-name  repo-id) (get-col-id "Library")))))

(defn compare-repositories [repositories]
  ;(nav/go-to ::page)
  (browser select ::type-select "Repositories")
  (browser check ::repo-auto-complete-radio)
  (browser click ::browse-button)
  (check-repositories repositories)
  (browser click ::repo-compare-button)
  (get-repo-content-search))

(defn select-type [type]
  (case type
    :packages  (browser select ::repo-result-type-select "Packages")
    :errata    (browser select ::repo-result-type-select "Errata")))

(defn select-view [set-type]
  (case set-type
    :all (browser select ::repo-result-filter-select   "Union")
    :shared (browser select ::repo-result-filter-select   "Intersection")
    :unique (browser select ::repo-result-filter-select   "Difference")))

(defn get-repo-packages [repo & {:keys [view] :or {view :packages} }]
  (compare-repositories [repo])
  (select-type view)
  (load-all-results)
  (get-result-packages))

(defn search-for-repositories [repo]
  ;(nav/go-to ::page)
  (browser select ::type-select "Repositories")
  (browser setText ::repo-search repo)
  (submit-browse-button)
  (get-grid-row-headers))

(defn search-for-packages [package]
  ;(nav/go-to ::page)
  (browser select ::type-select "Packages")
  (browser setText ::pkg-search package)
  (submit-browse-button)
  (get-grid-row-headers))

(defn select-content-type [content-type]
  ;; Navigate to content search page and select content type
  (let [ctype-map {:prod-type   "Products"
                   :repo-type   "Repositories"
                   :pkg-type    "Packages"
                   :errata-type "Errata"}
        ctype-str (ctype-map content-type)]
    ;(nav/go-to ::page)
    (browser select ::type-select ctype-str)))

(defn select-environments [envs]
  ;; Select environments (columns)
  (doseq [env envs]
    (let [col-locator (column env)]
      (sel/->browser (mouseOver ::column-selector)
                     (mouseOver col-locator)
                     (click col-locator)
                     (mouseOut ::column-selector)))))

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

  (select-content-type content-type)

  (select-environments envs)

  ;; Add content filters using auto-complete
  (doseq [[auto-comp-box add-button cont-items]
          [[::prod-auto-complete ::add-prod prods]
           [::repo-auto-complete ::add-repo repos]]]
    (doseq [cont-item cont-items]
      (autocomplete-adder-for-content-search auto-comp-box add-button cont-item)))

  ;; Add package
  (when-not (empty? pkg) (browser setText ::pkg-search pkg))

  ;; Add errata
  (when-not (empty? errata) (browser setText ::errata-search errata))

  (submit-browse-button)

  ;;extract and return content
  (->> "JSON.stringify(window.KT.content_search_cache.get_data());"
     (browser getEval)
     (json/read-json)))

(defn test-errata-popup-click [name]
  (browser click (span-text name))
  (browser mouseOver  ::errata-search)
  ;DOESNT CONTAIN NAME ANYMORE
  (assert/is (.contains (browser getText ::details-container) "Erratum"))
  (browser click (span-text name))
  (assert/is (= 0 (browser getXpathCount ::details-container))))

(defn test-errata-popup-hover [name]
  (assert/is
   (.contains
    (sel/->browser (mouseOver (span-text name))
                   (waitForElement  ::details-container) "4000"
                   (getText ::details-container))
    name))
  (browser mouseOut (.getLocator (span-text name)))
  (browser sleep 1000)
  (assert/is (= 0 (browser getXpathCount ::details-container))))

(defn get-errata-set  [type]
  (search-for-content :errata-type {:errata type})
  (get-grid-row-headers))

(defn get-repo-set  [search-string]
  (search-for-repositories search-string)
  (get-grid-row-headers))

(defn get-result-repos []
  (get-grid-row-headers))

(defn get-package-desc []
  (load-all-results)
  (zipmap (get-search-page-result-list-of-lists-xml "grid_row_headers")
          (get-search-page-result-list-of-lists-html "grid_content_window")))

(defn get-repo-desc []
  (get-search-page-result-list-of-lists-html "grid_content_window"))

(defn click-repo-desc [repo-name env-name]
  (browser click (repo-link (name-map-to-name (get-repo-search-data-name repo-name)) (get-col-id env-name))))
