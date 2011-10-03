(ns katello.tests.providers
  (:refer-clojure :exclude [fn])
  (:require (katello [tasks :as tasks]
                     [validation :as v])
            
            [clj-http.client :as http]
            [katello.api-tasks :as api]
            [clojure.java.io :as io])
  (:use [test.tree :only [fn data-driven]]
        [com.redhat.qe.verify :only [verify-that]]
        [com.redhat.qe.auto.bz :only [open-bz-bugs]]
        [katello.conf :only [config]]))

(def test-provider-name (atom nil))
(def test-product-name (atom nil))

(def create-custom 
  (fn [] (tasks/create-provider {:name (tasks/uniqueify "auto-cp")
                                :description "my description"
                                :type :custom})))

(def rename
  (fn [] (let [old-name (tasks/uniqueify "rename")
              new-name (tasks/uniqueify "newname")]
          (tasks/create-provider {:name old-name
                                  :description "my description"
                                  :type :custom})
          (tasks/edit-provider {:name old-name :new-name new-name})
          (let [current-providers (map :name (api/all-entities
                                              :provider
                                              "ACME_Corporation"))]
            (verify-that (and (some #{new-name} current-providers)
                              (not (some #{old-name} current-providers))))))))

(def delete
  (fn [] (let [provider-name (tasks/uniqueify "auto-provider-delete")]
          (tasks/create-provider {:name provider-name
                                  :description "my description"
                                  :type :custom})
          (tasks/verify-success
           #(tasks/delete-provider provider-name)))))

(def setup-custom
  (fn [] (tasks/create-provider {:name (reset! test-provider-name (tasks/uniqueify "cust"))
                                :description "my description"
                                :type :custom})))

(def create-product
  (fn [] (tasks/add-product {:provider-name @test-provider-name
                            :name (reset! test-product-name (tasks/uniqueify "prod"))
                            :description "test product"})))

(def delete-product
  (fn [] (let [product {:provider-name @test-provider-name
                       :name (tasks/uniqueify "deleteme")
                       :description "test product to delete"}]
          (tasks/add-product product)
          (tasks/delete-product product))))

(def create-repo
  (fn [] (tasks/add-repo {:provider-name @test-provider-name
                         :product-name @test-product-name
                         :name (tasks/uniqueify "repo")
                         :url "http://test.com/myurl"})))

(def delete-repo
  (fn [] (let [repo {:name (tasks/uniqueify "deleteme")
                    :provider-name @test-provider-name
                    :product-name @test-product-name
                    :url "http://my.fake/url"}]
          (tasks/add-repo repo)
          (tasks/delete-repo repo))))

(def manifest-tmp-loc "/tmp/manifest.zip")
(def redhat-provider-name "Red Hat")
(def manifest-testing-blockers
  (fn [_]
    (if-not (-> (api/lookup-by :name redhat-provider-name :provider (@config :admin-org))
            :repository_url
            (.contains "example.com"))
      [:manifest-already-uploaded]
      [])))

(def manifest-setup
  (fn [] 
    (with-open [instream (io/input-stream (java.net.URL. (@config :redhat-manifest-url)))
                outstream (io/output-stream manifest-tmp-loc)]
      (io/copy instream outstream))))

(def upload-manifest
  (fn []
    (let [provider-name redhat-provider-name]
      (tasks/edit-provider {:name provider-name
                            :repo-url (@config :redhat-repo-url)})
      (tasks/upload-subscription-manifest {:provider-name provider-name
                                           :file-path manifest-tmp-loc}))))

(def dupe-disallowed
  (fn []
    (v/duplicate-disallowed tasks/create-provider [{:name (tasks/uniqueify "dupe")
                                                    :description "mydescription"
                                                    :type :custom}])))

(def validation
    (fn [pred provider]
      (v/field-validation tasks/create-provider [provider] pred)))


(defn validation-data []
  (let [insert (fn [baseargs k v]
                 (assoc-in baseargs [1 k] v))
        [insert-name insert-desc insert-url] (map (fn [k] #(insert %1 k %2))
                                                  [:name :description :url])]
    (concat
     [[(v/expect-error :name-cant-be-blank) {:name nil
                                             :description "blah"
                                             :type :redhat
                                             :url "http://sdf.com"}]
                                
  
      [(v/expect-error :only-one-redhat-provider-per-org) {:name (tasks/uniqueify "mytestcp3")
                                                           :description nil
                                                           :type :redhat
                                                           :url "http://sdf.com"}]
      [tasks/success?  {:name (tasks/uniqueify "mytestcp4")
                        :description nil 
                        :type :custom
                        :url "http://sdf.com"}]]
     (v/variations
      [tasks/success?  {:name (tasks/uniqueify "mytestcp5")
                        :type :custom
                        :url "http://sdf.com"}]
      insert-desc v/javascript)
     
     (v/variations                  
      [(v/expect-error :name-no-leading-trailing-whitespace) {:description nil
                                                              :type :custom
                                                              :url "http://sdf.com"}]
      insert-name v/trailing-whitespace)

     (v/variations
      [(v/expect-error :name-must-not-contain-characters) {:description nil
                                                           :type :custom
                                                           :url "http://sdf.com"}]
      insert-name v/invalid-character)

     (for [test (v/variations  
                 [(v/expect-error :repository-url-invalid) {:name (tasks/uniqueify "mytestcp")
                                                            :description "blah"
                                                            :type :redhat}]
                 insert-url v/invalid-url)]
       (with-meta test {:blockers (open-bz-bugs "703528" "742983")
                        :description "Test that invalid URL is rejected."})))))

