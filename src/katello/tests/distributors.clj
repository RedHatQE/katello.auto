(ns katello.tests.distributors
  (:refer-clojure :exclude [fn])
  (:require [katello :as kt]
            (katello [tasks :refer :all]
                     [rest :as rest]
                     [ui :as ui]
                     [navigation :as nav]
                     [ui-common :as common]
                     [notifications :as notification]
                     [distributors :as distributor]
                     [blockers :refer [bz-bugs]])
            (test.tree [script :refer [defgroup deftest]])
            [clj-webdriver.taxi :as browser]
            [webdriver :as wd]
            [serializable.fn :refer [fn]]
            [test.assert :as assert]))

(defgroup distributor-tests

  (deftest "Create a Distributor"
    :uuid "1b5985df-437a-40dc-aac8-d979a39131b7"
    (with-unique [org  (kt/newOrganization {:name "test-org"})
                  dist (kt/newDistributor {:name "test-dist"})]
      (rest/create org)
      (ui/create (assoc dist :env (kt/newEnvironment {:name "Library" :org org})))))

  (deftest "Delete a Distributor"
    :uuid "e051b199-f3e0-4e04-aeb9-04b699653f9a"
    (with-unique [org  (kt/newOrganization {:name "test-org"})
                  dist (kt/newDistributor {:name "test-dist"})]
      (let [dist1 (assoc dist :env (kt/newEnvironment {:name "Library" :org org}))]
        (rest/create org)
        (ui/create dist1)
        (ui/delete dist1))))

  (deftest "Add custom info for a distributor"
    :data-driven true
    :uuid "9ff7fa88-6ee8-4425-9f5a-ff1896f4c6c5"
    (fn [keyname value success?]
      (with-unique [org (kt/newOrganization {:name "auto-org"})
                    env (kt/newEnvironment {:name "environment" :org org})
                    dist (kt/newDistributor {:name "test-dist" :env env})]
        (let [expected-res #(-> % :type (= :success))]
          (ui/create-all (list org env dist))
          (ui/update dist assoc :custom-info {keyname value})
          (assert/is (= (wd/text-present? keyname) success?))
          (assert/is (= (wd/text-present? keyname) success?)))))

    [["Platform" "RHEL6" true]
     [(random-ascii-string 255) (uniqueify "cust-value") true]
     [(uniqueify "cust-key") (random-ascii-string 255) true]
     [(uniqueify "cust-key") (random-ascii-string 256) false]
     [(random-unicode-string 10) (uniqueify "cust-value") true]
     [(uniqueify "cust-key") (random-unicode-string 10) true]

     (with-meta
       ["foo@!#$%^&*()" "bar_+{}|\"?<blink>hi</blink>" true]
       {:blockers (bz-bugs "951231")})

     ["foo@!#$%^&*()" "bar_+{}|\"?hi" true]])

  (deftest "Update custom info for a distributor"
    :data-driven true
    :blockers (bz-bugs "974166")
    :uuid "8f15a0dd-d925-4bed-9729-31ff2074d495"

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
    :uuid "6776a633-b0d2-4dec-9e71-07c9c352afcc"
    (with-unique [org (kt/newOrganization {:name "auto-org"})
                  env (kt/newEnvironment {:name "environment" :org org})
                  dist (kt/newDistributor {:name "test-dist" :env env})]
      (ui/create-all (list org env dist))
      (let [dist (ui/update dist assoc :custom-info {"fname" "FEDORA"})]
        (assert/is (wd/text-present? "fname"))
        (ui/update dist update-in [:custom-info] dissoc "fname")))))
