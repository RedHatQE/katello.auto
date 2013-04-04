(ns katello.fake-content
  (:require [clojure.java.io :as io]
            [katello :as kt]
            (katello [manifest :as manifest]
                     [tasks :refer [with-unique]]
                     [subscriptions :as subscriptions]
                     [conf :refer [config with-org]]
                     [organizations :as org]
                     [environments :as env]
                     [repositories :as repo]
                     [providers :as providers]
                     [rest :as rest]
                     [sync-management :as sync])))

(def nature (kt/newProduct {:name "Nature Enterprise" :provider kt/red-hat-provider}))
(def zoo (kt/newProduct {:name "Zoo Enterprise" :provider kt/red-hat-provider}))
(def some-repos (concat (for [reponame ["Nature Enterprise x86_64 1.0"
                                        "Nature Enterprise x86_64 1.1"
                                        "Nature Enterprise x86_64 6Server"]]
                          (kt/newRepository {:name reponame, :product nature}))
                        (for [reponame ["Zoo Enterprise x86_64 6.2"
                                        "Zoo Enterprise x86_64 6.3"
                                        "Zoo Enterprise x86_64 6.4"
                                        "Zoo Enterprise x86_64 5.8"
                                        "Zoo Enterprise x86_64 5.7"
                                        "Zoo Enterprise x86_64 6Server"]]
                          (kt/newRepository {:name reponame, :product zoo}))))

(def some-product-repos [{:name       "Nature Enterprise"
                          :repos      ["Nature Enterprise x86_64 1.0"
                                       "Nature Enterprise x86_64 1.1"
                                       "Nature Enterprise x86_64 6Server"]}
                         {:name     "Zoo Enterprise"
                          :repos    ["Zoo Enterprise x86_64 6.2"
                                     "Zoo Enterprise x86_64 6.3"
                                     "Zoo Enterprise x86_64 6.4"
                                     "Zoo Enterprise x86_64 5.8"
                                     "Zoo Enterprise x86_64 5.7"
                                     "Zoo Enterprise x86_64 6Server"]}])

(def subscription-names '("Nature Enterprise 8/5", "Zoo Enterprise 24/7"))

(let [prov (kt/newProvider {:name "Custom Provider"})
      com-nature (kt/newProduct {:name "Com Nature Enterprise"})
      weird-locals (kt/newProduct {:name "WeirdLocalsUsing 標準語 Enterprise"})
      many (kt/newProduct {:name "ManyRepository Enterprise"})]
  (concat (for [r [{:name "CompareZoo1" 
                    :url "http://fedorapeople.org/groups/katello/fakerepos/zoo/"}
                   {:name "CompareZoo2" 
                    :url "http://inecas.fedorapeople.org/fakerepos/zoo/"}
                   {:name "CompareZooNosync"
                    :unsyncable true
                    :url "http://inecas.fedorapeople.org/fakerepos/"}
                   {:name "ManyRepositoryA" 
                    :url "http://fedorapeople.org/groups/katello/fakerepos/zoo/"}]]
            (kt/newRepository (assoc r :product com-nature)))
          (for [r [{:name "洪" 
                    :url "http://fedorapeople.org/groups/katello/fakerepos/zoo/"}
                   {:name "Гесер" 
                    :url "http://inecas.fedorapeople.org/fakerepos/zoo/"}]]
            (kt/newRepository (assoc r :product weird-locals)))
          (for [r [{:name "ManyRepositoryA" 
                    :url "http://fedorapeople.org/groups/katello/fakerepos/zoo/"}
                   {:name "ManyRepositoryB" 
                    :url "http://fedorapeople.org/groups/katello/fakerepos/zoo/"}
                   {:name "ManyRepositoryC" 
                    :url "http://fedorapeople.org/groups/katello/fakerepos/zoo/"}
                   {:name "ManyRepositoryD" 
                    :url "http://fedorapeople.org/groups/katello/fakerepos/zoo/"}
                   {:name "ManyRepositoryE" 
                    :url "http://fedorapeople.org/groups/katello/fakerepos/zoo/"}]]
            (kt/newRepository (assoc r :product many)))))

(def custom-providers [{:name "Custom Provider"
                        :products [{:name "Com Nature Enterprise"
                                    :repos [{:name "CompareZoo1" 
                                             :url "http://fedorapeople.org/groups/katello/fakerepos/zoo/"}
                                            {:name "CompareZoo2" 
                                             :url "http://inecas.fedorapeople.org/fakerepos/zoo/"}
                                            {:name "CompareZooNosync"
                                             :unsyncable true
                                             :url "http://inecas.fedorapeople.org/fakerepos/"}
                                            {:name "ManyRepositoryA" 
                                             :url "http://fedorapeople.org/groups/katello/fakerepos/zoo/"}]}
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
                                             :url "http://fedorapeople.org/groups/katello/fakerepos/zoo/"}]}]}])

(def custom-provider [{:name "fedorapeople"
                       :products [{:name "safari-1_0"
                                   :repos [{:name "safari-x86_64" 
                                            :url "http://inecas.fedorapeople.org/fakerepos/cds/content/safari/1.0/x86_64/rpms/"}]}]}])


(def custom-errata-test-provider [{:name "Custom Errata Provider"
                                   :products [{:name "Com Errata Enterprise"
                                               :repos [{:name "ErrataZoo" 
                                                        :url "http://inecas.fedorapeople.org/fakerepos/new_cds/content/zoo/1.1/x86_64/rpms/"}]}]}])
                                                        ;:url "http://inecas.fedorapeople.org/fakerepos/severity_zoo/"}]}]}])


(def custom-env-test-provider [{:name "Custom Errata Provider"
                                :products [{:name "Com Errata Enterprise"
                                            :repos [{:name "ErrataZoo" 
                                                     :url "http://fedorapeople.org/groups/katello/fakerepos/zoo/"}]}
                                           {:name "Com Errata Inc"
                                            :repos [{:name "ErrataZoo" 
                                                     :url "http://fedorapeople.org/groups/katello/fakerepos/zoo/"}]}
                           
                                           {:name "WeirdLocalsUsing 標準語 Enterprise"
                                            :i18n true
                                            :repos [{:name "洪" 
                                                     :url "http://fedorapeople.org/groups/katello/fakerepos/zoo/"}
                                                    {:name "Гесер" 
                                                     :url "http://inecas.fedorapeople.org/fakerepos/zoo/"}]}]}])

(defn get-custom-repos [custom-providers-v & {:keys [filter-product? filter-repos?]
                                              :or {filter-product? (constantly true),
                                                   filter-repos? (constantly true)}}]
  (->> (for [provider custom-providers-v,
           product (:products provider), :when (filter-product? product)
           repo (:repos product), :when (filter-repos? repo)]
       repo)
     doall
     set
     (remove nil?)))


(defn get-all-custom-repos []
  (map :name (get-custom-repos custom-providers ))) 


(defn get-i18n-repos []
  (map :name (get-custom-repos custom-providers 
                               :filter-product? (fn [product] (contains? product :i18n))))) 


(def errata #{"RHEA-2012:0001" "RHEA-2012:0002"
              "RHEA-2012:0003" "RHEA-2012:0004"})

(declare local-clone-source)

(defn download-original-once []
  (defonce local-clone-source (let [dest (manifest/new-tmp-loc)]
                                (io/copy (-> config deref :redhat-manifest-url java.net.URL. io/input-stream)
                                         (java.io.File. dest))
                                (kt/newManifest {:file-path dest
                                                 :url (@config :redhat-repo-url)
                                                 :provider kt/red-hat-provider}))))

(defn prepare-org
  "Clones a manifest, uploads it to the given org (via api), and then
   enables and syncs the given repos"
  [repos]
  (with-unique [manifest (do (when-not (bound? #'local-clone-source)
                               (download-original-once))
                             local-clone-source) ]
    (rest/create (assoc manifest :provider (-> repos first :product :provider)))
    (sync/perform-sync repos)))

(defn setup-org [envs]
  (let [org (-> envs first :org)
        repos (for [r some-repos]
                (update-in r [:product :provider] assoc :org org))]
    (rest/create org)
    (doseq [e (kt/chain envs)]
      (rest/create e))
    (prepare-org repos)))
