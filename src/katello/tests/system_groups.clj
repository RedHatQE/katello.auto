(ns katello.tests.system-groups
  (:refer-clojure :exclude [fn])
  (:require [katello :as kt]
            (katello [ui :as ui]
                     [rest :as rest]
                     [navigation :as nav]
                     activation-keys
                     [client :as client]
                     [ui-common :as common]
                     [tasks :refer [uniqueify uniques expecting-error with-unique with-unique-ent]]
                     [systems :as system]
                     [system-groups :as group]
                     [conf :refer [*session-user* *session-org* config *environments*]]
                     [blockers :refer [bz-bugs]])
            [katello.client.provision :as provision]
            [katello.tests.useful :refer [create-recursive]]
            [test.tree.script :refer [defgroup deftest]]
            [serializable.fn :refer [fn]]
            [test.assert :as assert]
            [com.redhat.qe.auto.selenium.selenium :refer [browser]]))

(alias 'notif 'katello.notifications)

;; Functions

(defn some-group [] (kt/newSystemGroup {:name "group" :org *session-org*}))
(defn some-system [] (kt/newSystem {:name "system" :env (first *environments*)}))

(defn cancel-close-widget
  "Click 'cancel' on copy widget and widget should close properly 
   OR closing system-group widget should also close copy widget"
  [group {:keys [close-widget?]}]
  (nav/go-to group)
  (browser click ::group/copy)
  (browser click (if close-widget?
                   ::group/close
                   ::group/cancel-copy)))

(with-unique-ent "group" (some-group))

(defn assert-system-count
  "Assert the group g contains n systems."
  [g n]
  (assert/is (= n (group/system-count g))))

(defgroup sg-tests
  ;:blockers rest/katello-only
  
  (deftest "Create a system group"
    :uuid "0f484c32-c8a9-aff4-50e3-6106a509da4c"
    (with-unique-group g (ui/create g))

    (deftest "Copying with same system group name not allowed"
      :uuid "9632e065-08a2-ade4-0373-e99d54695c66"
      (with-unique-group g
        (ui/create g)
        (expecting-error (common/errtype ::notif/sg-name-taken-error)
                         (group/copy g (assoc g :description "copied system group")))))

    (deftest "Edit a system group"
      :uuid "f07f3b0a-8f56-1d14-d04b-390edd572bea"
      :data-driven true

      (fn [f & args]
        (with-unique-group g 
          (ui/create g)
          (apply ui/update g f args)) )

      [[assoc :limit 4]
       [assoc :limit 8, :description "updated description"]
       [assoc :description "updated description"]])


    (deftest "Edit system limit of a system group, then set back to unlimited"
      :uuid "5a34560b-86aa-4de4-3503-b78c447d3ec8"
      (with-unique-group g
        (ui/create g)
        (-> g
            (ui/update assoc :limit 4)
            (ui/update assoc :limit :unlimited)))
      
      (deftest "System group system limit validation"
        :uuid "fcc1b8f1-b4b4-be04-03db-11e824815d19"
        :data-driven true

        (fn [limit pred]
          (with-unique-group g
            (ui/create g)
            (expecting-error pred (ui/update g assoc :limit limit))))

        [(with-meta
           ["-1"   (common/errtype ::notif/max-systems-must-be-positive)]
           {:blockers (bz-bugs "848564")})
         ["-100" (common/errtype ::notif/max-systems-must-be-positive)]
         [""     (common/errtype ::notif/max-systems-must-be-positive)]
         ["0"    (common/errtype ::notif/max-systems-may-not-be-zero)]]))


    (deftest "Add a system to a system group"
      :uuid "338971f9-eb88-2324-76f3-1100125acfaa"
      :blockers (bz-bugs "845668")
      :data-driven true

      ;; Create various groups, changing the original properties if necessary
      ;; then add a system to it

      (fn [group-fn & args]
        (with-unique [s (some-system)
                      g (apply group-fn (some-group) args)]
          (rest/create s)
          (ui/create g)
          (ui/update g assoc :systems #{s})))
      [[identity]
       (with-meta
         [assoc :name "QE lab"]
         {:description "Add a system to a system group with a space in the group name"})])

    (deftest "Check that system count increments and decrements"
      :uuid "814a8b67-b22c-5034-a9bb-21cc6fa9b58b"
      :blockers (bz-bugs "857031")

      (with-unique [s (some-system)
                    g (some-group)]
        (rest/create s)
        (ui/create g)
        (let [g (ui/update g assoc :systems #{s})]
          (assert-system-count g 1)
          (ui/update g update-in [:systems] disj s)
          (assert-system-count g 0))))

    (deftest "Unregister a system & check count under sys-group details is -1"
      :uuid "73355767-1c3e-f1d4-1933-da5e329924bd"
      :blockers (conj (bz-bugs "959211") rest/katello-only)

      (with-unique [s1 (some-system)
                    g (some-group)]
        (rest/create-all (list s1 g))
        (provision/with-queued-client ssh-conn
          (client/register ssh-conn {:username (:name *session-user*)
                                     :password (:password *session-user*)
                                     :org "ACME_Corporation"
                                     :env (-> s1 kt/env :name)
                                     :force true})
          (let [s2 (assoc (some-system) :name (client/my-hostname ssh-conn))
                g (ui/update g assoc :systems #{s1 s2})]
            (assert-system-count g 2)
            (client/sm-cmd ssh-conn :unregister)
            (assert-system-count g 1)))))

    (deftest "Delete a system group"
      :uuid "41ed7910-64b6-2974-804b-052b86923291"
      :data-driven true

      (fn [data]
        (with-unique [g (merge (some-group) data)
                      s (some-system)]
          (rest/create-all (list s g))
          (ui/update g assoc :systems #{s})
          (ui/delete g)
          (assert/is (= (:also-remove-systems? g) (rest/not-exists? s)))))

      [[{:also-remove-systems? true}]
       [{:also-remove-systems? false}]])

    (deftest "Remove a system from copied system group"
      :uuid "c76060f1-fe9d-d524-a643-a33bafd0563c"
      :blockers (bz-bugs "857031")
      (with-unique [g (some-group)
                    s (some-system)]
        (rest/create s)
        (ui/create g)
        (let [g (ui/update g assoc :systems #{s})
              clone (update-in g [:name] #(str % "-clone"))]
          (group/copy g clone)
          (ui/update clone update-in [:systems] disj s))))

    (deftest "Systems removed from System Group can be re-added to a new group"
      :uuid "a4c075e5-777c-14a4-82b3-f35a748befcc"
      (with-unique [[g1 g2] (some-group)
                    s (some-system)]
        (rest/create-all (list g1 g2 s))
        (-> g1
            (ui/update assoc :systems #{s})
            (ui/update update-in [:systems] disj s))
        (ui/update g2 assoc :systems #{s})))

    (deftest "Reduce the max-limit after associating systems to max allowed limit"
      :uuid "41499e85-dada-ab04-de6b-5acea730652d"
      (with-unique [g (some-group)
                    [s1 s2] (some-system)]
        (rest/create-all (list g s1 s2))
        (ui/update g assoc :systems #{s1 s2})
        (expecting-error (common/errtype ::notif/systems-exceeds-group-limit)
                         (ui/update g assoc :limit 1))))

    (deftest "Add systems to sys group greater than the max allowed limit"
      :uuid "51f57a35-2d83-0974-78d3-a0e11230a5ca"
      (let [limit 2
            g (uniqueify (some-group))
            systems (take (inc limit) (uniques (some-system)))]
        (rest/create-all (conj systems g))
        (ui/update g assoc :limit limit)
        (expecting-error (common/errtype ::notif/bulk-systems-exceeds-group-limit)
                         (system/add-bulk-sys-to-sysgrp systems g))))

    (deftest "Register a system using AK & sys count should increase by 1"
      :uuid "8cff22f0-2a73-9bd4-2c53-109858c16751"
      :blockers (bz-bugs "959211")
      (with-unique [g (some-group)
                    s (some-system)
                    ak (kt/newActivationKey {:name "ak", :env (kt/env s)})]
        (rest/create-all (list g s ak))
        (ui/update g assoc :systems #{s})
        (ui/update ak assoc :system-group g)
        (provision/with-client "sys-count" ssh-conn
          (client/register ssh-conn
                           {:org (-> ak kt/org :name)
                            :activationkey (:name ak)})
          (assert-system-count g 2))))

    (deftest "cancel OR close widget"
      :uuid "9034c7d8-3d98-3ce4-524b-105dc0ceb100"
      :data-driven true
      :description "Closing the system-group widget should also close the copy widget (if its already open)
                         and 'cancel' copy widget should also work"
      (fn [opts]
        (with-unique-group g 
          (ui/create g)
          (cancel-close-widget g opts)))

      [[{:close-widget? true}]
       [{:close-widget? false}]])

    (deftest "Copy a system group"
      :uuid "8e5fd3cf-6c71-afd4-f093-8485a75a6034"
      (with-unique [g (some-group)
                    s (some-system)]
        (rest/create s)
        (ui/create g)
        (ui/update g assoc :systems #{s})
        (group/copy g (update-in g [:name] #(str % "-clone"))))
        
      (deftest "Delete a copied system group"
        :uuid "865f79c0-2f6d-1894-24b3-792738eb1073"
        :data-driven true
        (fn [data]
          (with-unique [g (merge (some-group) data)
                        s (some-system)]
            (rest/create s)
            (ui/create g)
            (ui/update g assoc :systems #{s})
            (let [clone (update-in g [:name] #(str % "-clone"))]
              (group/copy g clone)
              (ui/delete clone)
              (assert/is (= (:also-remove-systems? clone) (rest/not-exists? s))))))
        [[{:also-remove-systems? true}]
         [{:also-remove-systems? false}]])

      (deftest "cancel OR close widget"
        :uuid "8c21674f-304a-af64-d903-c08be18aefa6"
        :data-driven true
        :description "Closing the system-group widget should also close the copy widget (if its already open)
                         and 'cancel' copy widget should also work"
        (fn [opts]
          (with-unique-group g
            (ui/create g)
            (cancel-close-widget g opts)))

        [[{:close-widget? true}]
         [{:close-widget? false}]]))))

