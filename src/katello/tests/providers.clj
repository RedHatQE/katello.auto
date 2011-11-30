(ns katello.tests.providers
  (:refer-clojure :exclude [fn])
  (:require (katello [tasks :as tasks]
                     [validation :as v]
                     [api-tasks :as api])
            
            [clj-http.client :as http]
            [clojure.java.io :as io])
  (:use [test.tree.builder :only [data-driven]]
        [serializable.fn :only [fn]]
        [com.redhat.qe.verify :only [verify-that]]
        [com.redhat.qe.auto.bz :only [open-bz-bugs]]
        [katello.conf :only [config]]))

(def test-provider-name (atom nil))
(def test-product-name (atom nil))

(def create-custom 
  (fn [] (tasks/create-provider {:name (tasks/uniqueify "auto-cp")
                                :description "my description"})))

(def rename
  (fn [] (let [old-name (tasks/uniqueify "rename")
              new-name (tasks/uniqueify "newname")]
          (tasks/create-provider {:name old-name
                                  :description "my description"})
          (tasks/edit-provider {:name old-name :new-name new-name})
          (let [current-provider-names (map :name (api/with-admin
                                                    (api/all-entities
                                                     :provider)))]
            (verify-that (and (some #{new-name} current-provider-names)
                              (not (some #{old-name} current-provider-names))))))))

(def delete
  (fn [] (let [provider-name (tasks/uniqueify "auto-provider-delete")]
          (tasks/create-provider {:name provider-name
                                  :description "my description"})
          (tasks/verify-success
           #(tasks/delete-provider provider-name)))))

(def setup-custom
  (fn [] (tasks/create-provider {:name (reset! test-provider-name (tasks/uniqueify "cust"))
                                :description "my description"})))

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
(def manifest-uploaded? (atom nil))
(def manifest-testing-blockers
  (fn [_]
    (if @manifest-uploaded?
      [:manifest-already-uploaded]
      [])))

(def manifest-setup
  (fn [] 
    (when-not (reset! manifest-uploaded?
                      (tasks/manifest-already-uploaded?))
      (with-open [instream (io/input-stream (java.net.URL. (@config :redhat-manifest-url)))
                  outstream (io/output-stream manifest-tmp-loc)]
        (io/copy instream outstream)))))

(def upload-manifest
  (fn []
    (let [provider-name redhat-provider-name]
      (comment (tasks/edit-provider {:name provider-name
                             :repo-url (@config :redhat-repo-url)}))
      (tasks/upload-subscription-manifest  manifest-tmp-loc))))

(def dupe-disallowed
  (fn []
    (v/duplicate-disallowed tasks/create-provider [{:name (tasks/uniqueify "dupe")
                                                    :description "mydescription"}])))

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
                                             :url "http://sdf.com"}]
                                
  
      
      [tasks/success?  {:name (tasks/uniqueify "mytestcp4")
                        :description nil 
                        :url "http://sdf.com"}]]
     (v/variations
      [tasks/success?  {:name (tasks/uniqueify "mytestcp5") 
                        :url "http://sdf.com"}]
      insert-desc v/javascript)
     
     (v/variations                  
      [(v/expect-error :name-no-leading-trailing-whitespace) {:description nil 
                                                              :url "http://sdf.com"}]
      insert-name v/trailing-whitespace)

     (v/variations
      [(v/expect-error :name-must-not-contain-characters) {:description nil 
                                                           :url "http://sdf.com"}]
      insert-name v/invalid-character)

     (for [test (v/variations  
                 [(v/expect-error :repository-url-invalid) {:name (tasks/uniqueify "mytestcp")
                                                            :description "blah"}]
                 insert-url v/invalid-url)]
       (with-meta test {:blockers (open-bz-bugs "703528" "742983")
                        :description "Test that invalid URL is rejected."})))))

