(ns katello.fake-content
  (:require [clojure.java.io :as io]
            (katello [manifest :as manifest]
                     [conf :refer [config with-org]]
                     [organizations :as org]
                     [providers :as providers]
                     [ui-tasks :refer :all]
                     [sync-management :as sync])))


(def some-product-repos [{:name       "Nature Enterprise"
                          :repos      ["Nature Enterprise x86_64 1.0"
                                       "Nature Enterprise x86_64 1.1"]}
                         {:name     "Zoo Enterprise"
                          :repos    ["Zoo Enterprise x86_64 6.2"
                                     "Zoo Enterprise x86_64 6.3"
                                     "Zoo Enterprise x86_64 6.4"
                                     "Zoo Enterprise x86_64 5.8"
                                     "Zoo Enterprise x86_64 5.7"]}])

(def custom-providers [{:name "Custom Provider"
                        :products [{:name "Com Nature Enterprise"
                                    :repos [{:name "CompareZoo1" 
                                             :url "http://fedorapeople.org/groups/katello/fakerepos/zoo/"}
                                            {:name "CompareZoo2" 
                                             :url "http://inecas.fedorapeople.org/fakerepos/zoo/"}]}
                                   {:name "WeirdLocalsUsing 標準語 Enterprise"
                                    :i18n true
                                    :repos [{:name "洪" 
                                             :url "http://fedorapeople.org/groups/katello/fakerepos/zoo/"}
                                            {:name "Гесер" 
                                             :url "http://inecas.fedorapeople.org/fakerepos/zoo/"}]}
                                   {:name "ManyRepository Enterprise"
                                    :repos [{:name "ManyRepositoryA" 
                                             :url "http://fedorapeople.org/groups/katello/fakerepos/zoo/"}
                                            {:name "ManyRepositoryB" 
                                             :url "http://fedorapeople.org/groups/katello/fakerepos/zoo/"}
                                            {:name "ManyRepositoryC" 
                                             :url "http://fedorapeople.org/groups/katello/fakerepos/zoo/"}
                                            {:name "ManyRepositoryD" 
                                             :url "http://fedorapeople.org/groups/katello/fakerepos/zoo/"}
                                            {:name "ManyRepositoryE" 
                                             :url "http://fedorapeople.org/groups/katello/fakerepos/zoo/"}
                                           ]}]}])

(defn get-custom-repos [custom-providers-v & {:keys [filter-product?] :or {filter-product? (fn [product] true)}}]
  (set (remove nil? (flatten 
    (doall (for [provider custom-providers-v]
     (for [product (:products provider)]
       (when (filter-product? product)
        (for [ repo (:repos product)]
          (:name repo))))))))))


(defn get-all-custom-repos []
  (get-custom-repos custom-providers )) 
  

(defn get-i18n-repos []
  (get-custom-repos custom-providers 
                    :filter-product? (fn [product] (contains? product :i18n)))) 
  

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
    (with-org org-name
      (org/switch)
      (manifest/upload-new-cloned dl-loc {:repository-url (@config :redhat-repo-url)})
      (enable-redhat-repositories repos)
      (sync/perform-sync repos))))

(defn prepare-org-custom-provider
  "Clones a manifest, uploads it to the given org, and then enables
  and syncs the given repos"
  [org-name providers]
    (with-org org-name
      (org/switch)
      (doseq [provider providers]
        (providers/create {:name (provider :name)})
        (doseq [product (provider :products)]  
          (providers/add-product {:provider-name (provider :name) 
                                  :name (product :name)})
          (doseq [repo (product :repos)]
            (providers/add-repo {:provider-name (provider :name)  
                                 :product-name (product :name)
                                 :name (repo :name) 
                                 :url (repo :url)})))) 
            (sync/perform-sync (get-custom-repos custom-providers ))))
