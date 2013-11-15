(ns katello.page-parsing
  (:require [clojure.data.json  :as json]
            [clojure.xml :as xml]
            [clojure.zip :as zip]
            [clojure.data.zip :as zf]
            [webdriver :as browser]
            [clojure.data.zip.xml :as zfx]
            [clojure.string :refer [split triml trim]]
            [clojure.walk :refer [postwalk] ]
            (katello [navigation :as nav]
                     [organizations :as org]
                     [tasks         :refer :all]
                     [ui            :as ui]
                     [ui-common     :as common]
                     [notifications :as notification]
                     [conf          :refer [config]])
            [slingshot.slingshot :refer [throw+ try+]]
            [pl.danieljanus.tagsoup :refer [parse-xml]]
            [test.assert :as assert])
  (:import [org.ccil.cowan.tagsoup Parser]
           [java.io InputStream File FileInputStream ByteArrayInputStream BufferedInputStream InputStreamReader BufferedReader]
           [org.xml.sax InputSource]
           [java.text SimpleDateFormat]
           [java.io ByteArrayInputStream]))

(defn get-string-of-html-element [id]
  (->> id
       (format "return window.document.getElementById('%s').innerHTML;")
       (browser/execute-script)
       (format "<root>%s</root>")))

(defn get-string-of-body-element []
  (->>
   "window.document.getElementsByTagName('body')[0].innerHTML;"
   (browser/execute-script)
   (format "<body>%s</body>")
   ))

(defn search-in-zip [query node]
  (loop [q query
         n node]
    (if (q n)
      n
      (if (zip/end? n)
        nil
        (recur q (zip/next n))))))


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
     (and (map? %) (contains? % :attrs) (= (-> % :attrs :class) "dot_icon-black")) (into empty-coll ["++"])
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

(defn postwalk-trim [tree]
  (postwalk #(if (string? %)
               (trim %)
               %)
            tree))

(defn postwalk-rm [what tree]
  (postwalk #(if (= % what)
               nil
               %)
            tree))

(defn get-search-page-result-list-of-lists [xml-zip]
  (->> xml-zip
       zip/node
       (node-content-as [])
       (remove-nil-and-empty vector? [])
       normalize-nodes
       (postwalk #(cond (= "--" %) false
                        (= "++" %) true
                        :else %))))
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

