(ns katello.tests.distributors
  (:refer-clojure :exclude [fn])
  (:require [katello :as kt]
            (katello [tasks :refer :all]
                     [rest :as rest]
                     [ui :as ui]
                     [navigation :as nav]
                     [ui-common :as common]
                     [notifications :as notification]
                     [distributors :as distributor])
            (test.tree [script :refer [defgroup deftest]])
            [com.redhat.qe.auto.selenium.selenium :refer [browser]]
            [bugzilla.checker :refer [open-bz-bugs]]
            [serializable.fn :refer [fn]]
            [test.assert :as assert]))

(defgroup distributor-tests

  (deftest "Create a Distributor"
    (with-unique [org  (kt/newOrganization {:name "test-org"})
                  dist (kt/newDistributor {:name "test-dist"})]
      (rest/create org)
      (ui/create (assoc dist :env (kt/newEnvironment {:name "Library" :org org})))))

  (deftest "Delete a Distributor"
    (with-unique [org  (kt/newOrganization {:name "test-org"})
                  dist (kt/newDistributor {:name "test-dist"})]
      (let [dist1 (assoc dist :env (kt/newEnvironment {:name "Library" :org org}))]
        (rest/create org)
        (ui/create dist1)
        (ui/delete dist1))))
  
  (deftest "Add custom info for a distributor"   
    :data-driven true    
    (fn [keyname value success?]
      (with-unique [org (kt/newOrganization {:name "auto-org"})
                    env (kt/newEnvironment {:name "environment" :org org})
                    dist (kt/newDistributor {:name "test-dist" :env env})]
        (let [expected-res #(-> % :type (= :success))]
          (ui/create-all (list org env dist))
          (ui/update dist assoc :custom-info {keyname value})
          (assert/is (= (browser isTextPresent keyname) success?))
          (assert/is (= (browser isTextPresent keyname) success?)))))
    
    [["Platform" "RHEL6" true]
     [(random-string (int \a) (int \z) 255) (uniqueify "cust-value") true]
     [(uniqueify "cust-key") (random-string (int \a) (int \z) 255) true]
     [(uniqueify "cust-key") (random-string (int \a) (int \z) 256) false]
     [(random-string 0x0080 0x5363 10) (uniqueify "cust-value") true]
     [(uniqueify "cust-key") (random-string 0x0080 0x5363 10) true]

     (with-meta
       ["foo@!#$%^&*()" "bar_+{}|\"?<blink>hi</blink>" false]
       {:blockers (open-bz-bugs "951231")})

     ["foo@!#$%^&*()" "bar_+{}|\"?hi" true]])
  
  (deftest "Update custom info for a distributor"   
    :data-driven true
    :blockers (open-bz-bugs "974166")
    
    (fn [input-loc new-value save?]
      (with-unique [org (kt/newOrganization {:name "auto-org"})
                    env (kt/newEnvironment {:name "environment" :org org})
                    dist (kt/newDistributor {:name "test-dist" :env env})]
        (let [expected-res #(-> % :type (= :success))]
          (ui/create-all (list org env dist))
          (ui/update dist assoc :custom-info {"fname" "redhat"})
          (expecting-error expected-res
            (nav/go-to ::distributor/custom-info-page dist)              
            (common/save-cancel ::distributor/save-button ::distributor/cancel-button input-loc new-value save?)))))
    
    [[(distributor/value-text "fname") "fedora" false]
     [(distributor/value-text "fname") "Schrodinger's cat" true]])
  
  (deftest "Delete custom info for a distributor"
    (with-unique [org (kt/newOrganization {:name "auto-org"})
                  env (kt/newEnvironment {:name "environment" :org org})
                  dist (kt/newDistributor {:name "test-dist" :env env})] 
      (ui/create-all (list org env dist))
      (let [dist (ui/update dist assoc :custom-info {"fname" "FEDORA"})]
        (assert/is (browser isTextPresent "fname"))
        (ui/update dist update-in [:custom-info] dissoc "fname")))))
