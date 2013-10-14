(ns katello.fake-content
  (:require [katello :as kt]
            [katello.tests.useful :refer [create-all-recursive]]
            (katello [manifest :as manifest]
                     [tasks :refer [with-unique]]
                     [subscriptions :as subscriptions]
                     [rest :as rest]
                     [conf :refer [config]]
                     [organizations :as org]
                     [environments :as env]
                     [repositories :as repo]
                     [providers :as providers]
                     [ui :as ui]
                     [sync-management :as sync])))

(def enable-repos 
            {:allrepos '(["Nature Enterprise x86_64 1.0"
                          "Nature Enterprise x86_64 1.1"
                          "Nature Enterprise x86_64 6Server"]
                         ["Zoo Enterprise x86_64 6.2"
                          "Zoo Enterprise x86_64 6.3"
                          "Zoo Enterprise x86_64 6.4"
                          "Zoo Enterprise x86_64 5.8"
                          "Zoo Enterprise x86_64 5.7"
                          "Zoo Enterprise x86_64 6Server"]) 
             :allreposets '("Nature Enterprise" 
                            "Zoo Enterprise") 
             :allprds     '("Nature Enterprise" 
                            "Zoo Enterprise")
             :repo-type      "other" 
             :deselect?      false})

(def enable-nature-repos 
            {:allrepos '(["Nature Enterprise x86_64 1.0"
                          "Nature Enterprise x86_64 1.1"
                          "Nature Enterprise x86_64 6Server"]) 
             :allreposets '("Nature Enterprise") 
             :allprds     '("Nature Enterprise")
             :repo-type      "other" 
             :deselect?      false})


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
                                   {:name "Weird Enterprise"
                                            :i18n true
                                            :repos [{:name "China" 
                                                     :url "http://fedorapeople.org/groups/katello/fakerepos/zoo/"}
                                                    {:name "Russia" 
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
                                            :repos [{:name "ErrataZoo2" 
                                                     :nosync true
                                                     :url "http://fedorapeople.org/groups/katello/fakerepos/zoo/"}]}
                           
                                           {:name "Weird Enterprise"
                                            :i18n true
                                            :repos [{:name "China" 
                                                     :url "http://fedorapeople.org/groups/katello/fakerepos/zoo/"}
                                                    {:name "Russia" 
                                                     :url "http://inecas.fedorapeople.org/fakerepos/zoo/"}]}]}])
    
(defn repo-list-from-tree [tree org]
  (into [] (flatten
    (for [provider tree]
      (for [product (provider :products)] 
        (for [repo (product :repos)]
          (kt/newRepository 
             {:name (repo :name) 
              :nosync (repo :nosync)
             :url (repo :url)
             :product (kt/newProduct 
                        {:name (product :name) 
                         :i18n (product :i18n)
                         :provider (kt/newProvider 
                                     {:name (provider :name) 
                                      :org org})})}))))))) 

(def custom-repos  (repo-list-from-tree custom-providers nil))

(defn prepare-org-custom-provider [org tree]
  (let [repolist (repo-list-from-tree tree org)]
    (rest/create-all-recursive repolist)
    (sync/perform-sync (remove :nosync repolist) {:rest true}))) 

(defn get-all-custom-repo-names []
  (map :name (repo-list-from-tree custom-providers nil)))

(defn get-i18n-repos []
  (filter #(-> % kt/product :i18n) (repo-list-from-tree custom-providers nil)))

(defn get-i18n-repo-names []
  (map :name (get-i18n-repos))) 

(def errata #{"RHEA-2012:0001" "RHEA-2012:0002"
              "RHEA-2012:0003" "RHEA-2012:0004"})
