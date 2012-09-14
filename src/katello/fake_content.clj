(ns katello.fake-content
  (:require [clojure.java.io :as io]
            (katello [manifest :as manifest]
                     [conf :refer [config]]
                     [organizations :as org]
                     [ui-tasks :refer :all]
                     [sync-management :as sync])))


(def some-product-repos [{:name       "Nature Enterprise"
                  :poolName   "Nature Enterprise"
                  :repos      ["Nature Enterprise x86_64 1.0"
                               "Nature Enterprise x86_64 1.1"]}
                 {:name     "Zoo Enterprise"
                  :poolName "Zoo Enterprise"
                  :repos    ["Zoo Enterprise x86_64 6.2"
                             "Zoo Enterprise x86_64 6.3"
                             "Zoo Enterprise x86_64 6.4"
                             "Zoo Enterprise x86_64 5.8"
                             "Zoo Enterprise x86_64 5.7"]}])

(def errata #{"RHEA-2012:0001" "RHEA-2012:0002"
              "RHEA-2012:0003" "RHEA-2012:0004"})

(defn download-original [dest]
  (io/copy (-> config deref :redhat-manifest-url java.net.URL. io/input-stream)
           (java.io.File. dest)))

(defn prepare-org
  "Clones a manifest, uploads it to the given org, and then enables
  and syncs the given repos"
  [org-name repos]
  (let [dl-loc (manifest/new-tmp-loc)]
    (download-original dl-loc)
    (org/execute-with org-name
      (manifest/upload-new-cloned dl-loc {:repository-url (@config :redhat-repo-url)})
      (enable-redhat-repositories repos)
      (sync/perform-sync repos))))

