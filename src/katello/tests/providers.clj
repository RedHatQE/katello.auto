(ns katello.tests.providers
  (:refer-clojure :exclude [fn])
  (:require [katello.api-tasks :as api]
            [clj-http.client :as http]
            [clojure.java.io :as io]
            [katello.tests.e2e :as e2e])
  (:use katello.tasks
        katello.validation
        test.tree.script
        [test.tree.builder :only [data-driven]]
        [serializable.fn :only [fn]]
        [com.redhat.qe.verify :only [verify-that]]
        [bugzilla.checker :only [open-bz-bugs]]
        [katello.conf :only [config]]))

;; Load more tests groups into this namespace
(load "providers/custom")
(load "providers/redhat")

;; Functions

(defn get-all-providers []
  (map :name (api/with-admin (api/all-entities :provider))))

(defn verify-provider-renamed [old-name new-name]
  (let [current-provider-names (get-all-providers)]
    (verify-that (and (some #{new-name} current-provider-names)
                      (not (some #{old-name} current-provider-names))))))

(defn with-n-new-orgs
  "Create n organizations with unique names. Then calls function f
  with a unique name, and the org names. This is useful for verifying
  whether the same name for an entity can be used across orgs.
  Switches back to admin org after f is called."
  [n f]
  (let [ent-name (uniqueify "samename")
        orgs (take n (unique-names "ns-org"))]
    (doseq [org orgs]
      (api/with-admin (api/create-organization org)))
    (try
      (f ent-name orgs)
      (finally (switch-org (@config :admin-org))))))

(defn with-two-providers
  "Create two providers with unique names, and call f with a unique
  entity name, and the provider names. Used for verifying (for
  instance) that products with the same name can be created in 2
  different providers."
  [f]
  (let [ent-name (uniqueify "samename")
        providers (take 2 (unique-names "ns-provider"))]
    (doseq [provider providers]
      (api/with-admin (api/create-provider provider)))
    (f ent-name providers)))

(defn create-same-provider-in-multiple-orgs
  [prov-name orgs]
  (doseq [org orgs]
    (switch-org org)
    (create-provider {:name prov-name})))

(defn get-validation-data
  "a totally misguided attempt on my part to generate test data.
  Generating data is fine, but this code is unreadable even to me,
  should probably replace this code with the data it produces, and be
  done with it. Only issue there is we can't hardcode
  provider/product/repo names. -jweiss" []
  (let [insert (fn [baseargs k v]
                 (assoc-in baseargs [1 k] v))
        [insert-name insert-desc insert-url] (map (fn [k] #(insert %1 k %2))
                                                  [:name :description :url])]
    (concat
     [[(expect-error :name-cant-be-blank) {:name nil :description "blah" :url "http://sdf.com"}]
      [success?  {:name (uniqueify "mytestcp4") :description nil :url "http://sdf.com"}]]

     (variations [success?  {:name (uniqueify "mytestcp5") :url "http://sdf.com"}] insert-desc javascript)
     (variations [(expect-error :name-no-leading-trailing-whitespace) {:description nil :url "http://sdf.com"}] insert-name trailing-whitespace)
     (variations [(expect-error :name-must-not-contain-characters) {:description nil :url "http://sdf.com"}] insert-name invalid-character)

     (for [test (variations [(expect-error :repository-url-invalid) {:name (uniqueify "mytestcp") :description "blah"}]
                              insert-url invalid-url)]
       (with-meta test {:blockers (open-bz-bugs "703528" "742983")
                        :description "Test that invalid URL is rejected."})))))

;; Tests

(defgroup all-provider-tests
  
  (deftest "Create a custom provider" 
    (create-provider {:name (uniqueify "auto-cp")
                      :description "my description"})


    (deftest "Cannot create two providers in the same org with the same name"
      (duplicate-disallowed create-provider [{:name (uniqueify "dupe")
                                                  :description "mydescription"}]))
    
    (deftest "Provider validation"
      :data-driven true
      :description "Creates a provider using invalid data, and
                    verifies that an error notification is shown in
                    the UI."
      validation
      (get-validation-data))

    
    (deftest "Rename a custom provider"
      (with-unique [old-name  "rename"
                    new-name  "newname"]
        (create-provider {:name old-name :description "my description"})
        (edit-provider {:name old-name :new-name new-name})
        (verify-provider-renamed old-name new-name)))
    
    
    (deftest "Delete a custom provider"
      (with-unique [provider-name "auto-provider-delete"]
        (create-provider {:name provider-name :description "my description"})
        (delete-provider provider-name)))

    
    (deftest "Create two providers with the same name, in two different orgs"
      (with-n-new-orgs 2 create-same-provider-in-multiple-orgs)))

  )

 



















(def validation
    (fn [pred provider]
      (field-validation create-provider [provider] pred)))




