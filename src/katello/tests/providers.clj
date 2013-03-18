(ns katello.tests.providers
  (:refer-clojure :exclude [fn])
  (:require [katello.api-tasks :as api]
            [test.tree.script  :refer [deftest defgroup]]
            [serializable.fn   :refer [fn]]
            [test.assert       :as assert]
            [bugzilla.checker  :refer [open-bz-bugs]]
            (katello [rest :as rest]
                     [ui :as ui]
                     [tasks           :refer :all]
                     [ui-common       :as common]
                     [notifications   :refer [success?]]
                     [organizations   :as organization]
                     [sync-management :as sync] 
                     [repositories    :as repo]
                     [providers       :as provider]
                     [gpg-keys        :as gpg-key]
                     [package-filters :as filter]
                     [validation      :refer :all]
                     [conf            :as conf])
            [katello.tests.useful :refer [create-series create-recursive]]))

;; Constants

(def tmp-gpg-keyfile (tmpfile "output.txt"))

;; Functions

(defn verify-provider-renamed
  "Verifies that a provider named old-prov doesn't exist, that that a
  provider named new-prov does exist."
  [old-prov new-prov]
  (assert (and (rest/exists? new-prov)
               (not (rest/exists? old-prov)))))

(defn create-same-provider-in-multiple-orgs
  "Create providers with the same name in multiple orgs."
  [prov-name orgs]
  (doseq [org orgs]
    (organization/switch org)
    (provider/create {:name prov-name})))

(defn validation
  "Attempts to create a provider and validates the result using
   pred."
  [provider pred]
  (expecting-error pred (ui/create (katello/newProvider provider))))

(defn get-validation-data
  []
  (concat
   [[{:name nil
      :description "blah"
      :url "http://sdf.com"} (common/errtype :katello.notifications/name-cant-be-blank)]

    [{:name (uniqueify "mytestcp4")
      :description nil
      :url "http://sdf.com"} success?]]

   (for [js-str javascript-strings]
     [{:name (uniqueify "mytestcp5")
       :description js-str
       :url "http://sdf.com"}  success?])
    
   (for [trailing-ws-str trailing-whitespace-strings]
     [{:name trailing-ws-str
       :description nil
       :url "http://sdf.com"} (common/errtype :katello.notifications/name-no-leading-trailing-whitespace)])
    
   (for [inv-char-str invalid-character-strings]
     [{:name inv-char-str
       :description nil
       :url "http://sdf.com"} (common/errtype :katello.notifications/name-must-not-contain-characters)])))

;; Tests

(defgroup gpg-key-tests
  :group-setup #(spit tmp-gpg-keyfile "test")
  
  (deftest "Create a new GPG key from text input"
    :blockers api/katello-only
    
    (-> {:name "test-key-text"
         :contents "asdfasdfasdfasdfasdfasdfasdf"}
        katello/newGPGKey
        uniqueify
        ui/create))
  
  (deftest "Create a new GPG key from file"
    :blockers (open-bz-bugs "835902" "846432")

    (-> {:name "test-key-file"
         :filename tmp-gpg-keyfile}
        katello/newGPGKey
        uniqueify
        ui/create)
        
    (deftest "Delete existing GPG key" 
      (doto (-> {:name "test-key"
                 :filename tmp-gpg-keyfile}
                katello/newGPGKey
                uniqueify)
        ui/create
        ui/delete))))

(defgroup provider-tests
  
  (deftest "Create a custom provider" 
    (-> {:name "auto-cp"
         :description "my description"
         :org conf/*session-org*}
        katello/newProvider
        uniqueify
        ui/create)


    (deftest "Cannot create two providers in the same org with the same name"
      (with-unique [provider (katello/newProvider {:name "dupe"
                                                   :org conf/*session-org*})]
        (expecting-error-2nd-try duplicate-disallowed
                                 (ui/create provider))))
    
    (deftest "Provider validation"
      :data-driven true
      :description "Creates a provider using invalid data, and
                    verifies that an error notification is shown in
                    the UI."
      validation
      (get-validation-data))

    
    (deftest "Rename a custom provider"
      (let [provider (-> {:name "rename"
                          :description "my description"
                          :org conf/*session-org*}
                         katello/newProvider
                         uniqueify)]
        (ui/create provider)
        (let [updated (ui/update assoc :name "newname")]
          (verify-provider-renamed provider updated))))
    
    (deftest "Delete a custom provider"
      (doto (-> {:name "auto-provider-delete"
                 :org conf/*session-org*}
                katello/newProvider
                uniqueify)
        (ui/create)
        (ui/delete)))

    
    (deftest "Create two providers with the same name, in two different orgs"
      (with-unique [provider (katello/newProvider {:name "prov"})]
        (doseq [org (->> {:name "prov-org"}
                         katello/newOrganization
                         uniques
                         (take 2))]
          (create-recursive (assoc provider :org org))))))

  gpg-key-tests)


