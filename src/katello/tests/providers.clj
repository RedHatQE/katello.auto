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
        [bugzilla.checker :only [open-bz-bugs]]
        [katello.conf :only [config]]))

(def test-provider-name (atom nil))
(def test-product-name (atom nil))

(defn with-two-orgs
  "Create two orgs with unique names, and call f with a unique entity
  name, and the org names. Used for verifying (for instance) that
  envs or providers with the same name can be created in 2 different
  orgs.  Switches back to admin org after f is called."
  [f]
  (let [ent-name (tasks/uniqueify "samename")
        orgs (take 2 (tasks/unique-names "ns-org"))]
    (doseq [org orgs]
      (api/with-admin (api/create-organization org)))
    (try
      (f ent-name orgs)
      (finally (tasks/switch-org (@config :admin-org)) ))))


(defn with-two-providers
  "Create two providers with unique names, and call f with a unique
  entity name, and the provider names. Used for verifying (for
  instance) that products with the same name can be created in 2
  different providers."
  [f]
  (let [ent-name (tasks/uniqueify "samename")
        providers (take 2 (tasks/unique-names "ns-provider"))]
    (doseq [provider providers]
      (api/with-admin (api/create-provider provider)))
    (f ent-name providers)))

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

(def namespace-provider
  (fn []
    (with-two-orgs (fn [prov-name orgs]
                     (doseq [org orgs]
                       (tasks/switch-org org)
                       (tasks/create-provider {:name prov-name}))))))

(def namespace-product-in-provider
  (fn []
    (v/field-validation with-two-providers
                        [(fn [product-name providers]
                            (doseq [provider providers]
                              (tasks/add-product {:provider-name provider
                                                  :name product-name})))]
                        (v/expect-error :product-must-be-unique-in-org))))

(def namespace-product-in-org
  (fn []
    (with-two-orgs
      (fn [product-name orgs]
        (doseq [org orgs]
          (tasks/switch-org org)
          (let [provider-name (tasks/uniqueify "prov")]
           (api/with-admin
             (api/with-org org
               (api/create-provider provider-name))
             (tasks/add-product {:provider-name provider-name
                                 :name product-name}))))))))

(def manifest-tmp-loc "/tmp/manifest.zip")
(def redhat-provider-test-org (atom nil))

(def manifest-setup
  (fn [] 
    (let [org-name (reset! redhat-provider-test-org (tasks/uniqueify "rh-test"))]
      (api/with-admin (api/create-organization org-name))
      (with-open [instream (io/input-stream (java.net.URL. (@config :redhat-manifest-url)))
                  outstream (io/output-stream manifest-tmp-loc)]
        (io/copy instream outstream)))))

(def upload-manifest
  (fn []
    (comment (tasks/with-org @redhat-provider-test-org
               (tasks/upload-subscription-manifest manifest-tmp-loc
                                                   {:repository-url (@config :redhat-repo-url)})))
    (api/with-admin (api/with-org @redhat-provider-test-org
                      (api/upload-manifest manifest-tmp-loc (@config :redhat-repo-url))))))

(def enable-redhat-repos
  (fn []
    (let [repos ["Nature Enterprise x86_64 5Server"
                 "Nature Enterprise x86_64 6Server"]]
      
      (tasks/with-org @redhat-provider-test-org
        (tasks/enable-redhat-repositories repos)
        (tasks/navigate :sync-status-page)
        (verify-that (every? nil? (map tasks/sync-complete-status repos)))))))

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

