(ns katello.tests.system-groups
  (:refer-clojure :exclude [fn])
  (:require [katello :as kt]
            (katello [ui :as ui]
                     [rest :as rest]
;                     [organizations :as org]
                     [activation-keys :as ak]
                     [client :as client]
                     [ui-common :as common]
                     [tasks :refer [uniqueify uniques expecting-error with-unique with-unique-ent]]
                     [systems :as system]
                     [system-groups :as group]
                     [conf :refer [*session-user* config *environments*]])
            [katello.client.provision :as provision]
            [katello.tests.useful :refer [create-recursive]]
            (test.tree [script :refer [defgroup deftest]]
                       [builder :refer [union]])
            [serializable.fn :refer [fn]]
            [test.assert :as assert]
            [bugzilla.checker :refer [open-bz-bugs]]))

(alias 'notif 'katello.notifications)
;; Functions

<<<<<<< HEAD
(defn some-group [] (kt/newSystemGroup {:name "group" :env (first *environments*)}))
(defn some-system [] (kt/newSystem {:name "system" :env (first *environments*)}))
=======
(defn step-create
  [{:keys [group-name]}]
  (group/create group-name {:description "rh system group"}))

(defn step-edit
  [{:keys [group-name group-new-limit group-new-description]}]
  (group/edit group-name {:new-limit group-new-limit
                          :description group-new-description}))

(defn step-add-new-system
  "Creates a system and system group, adds the system to the system group."
  [{:keys [group-name system-name] :as m}]
  (system/create system-name {:sockets "1"
                              :system-arch "x86_64"})
  (step-create m)
  (group/add-to group-name system-name))

(defn mkstep-remove
  "Creates a fn to remove a system group given a request map. Optional
   arg which-group determines which key contains the group to remove.
   Defaults to :system-group."
  [which-group]
  (fn [{:keys [group-name also-remove-systems?] :as req}]
    (group/remove (req which-group)
                  {:also-remove-systems? also-remove-systems?})))

(def step-remove (mkstep-remove :group-name))
(def step-remove-copy (mkstep-remove :copy-name))

(defn step-verify-system-presence
  "Verifies that the system is either present, or not present after
   removing its system group. Depends on whether :also-remove-systems?
   is true in the input map (if true, then verifies system is *not*
   present."
  [{:keys [system-name also-remove-systems?]}]
  (let [all-system-names (map :name (api/all-entities :system))]
    (if also-remove-systems?
      (assert/is (not (some #{system-name} all-system-names)))
      (assert/is (some #{system-name} all-system-names)))))

(defn step-copy
  "Copies a system group with a hardcoded description."
  [{:keys [group-name copy-name]}]
  (group/copy group-name copy-name {:description "copied system group"}))

(defn step-remove-sys-from-copied-system-group
  "Remove the system from copied system group."
  [{:keys [copy-name system-name]}]
  (group/remove-from copy-name system-name))

(defn step-add-bulk-sys-to-sysgrp
  [{:keys [system-names group-name]}]
  (let [system-names (take 2 (unique-names "mysys"))]
    (katello.tests.systems/create-multiple-systems system-names)
    (expecting-error (common/errtype :katello.notifications/bulk-systems-exceeds-group-limit)
                     (system/add-bulk-sys-to-sysgrp system-names group-name))))

(defn step-add-sys-to-sysgroup-with-new-limit
  "Creates a system, adds the system to existing system group."
  [{:keys [group-name system-name] :as m}]
  (with-unique [system-name "test1"]
    (do
      (system/create system-name {:sockets "1"
                                  :system-arch "x86_64"})
      (group/edit group-name {:new-limit 1})
      (expecting-error (common/errtype :katello.notifications/add-systems-greater-than-allowed)
                       (group/add-to group-name system-name)))))

(defn step-add-sys-to-sysgroup-from-right-pane
  "Creates a system, adds the system to existing system group from right pane"
  [{:keys [group-name system-name] :as m}]
  (with-unique [system-name "test-sys"]
    (system/create system-name {:sockets "1"
                                :system-arch "x86_64"})
    (expecting-error [:type :katello.systems/selected-sys-group-is-unavailable]
                     (system/add-sys-to-sysgrp system-name group-name))))

(defn step-add-exiting-system-to-new-group
  "Create a system group and add existing system (which was earlier member of some other group)"
  [{:keys [new-group system-name] :as m}]
  (group/create new-group {:description "rh system group"})
  (group/add-to new-group system-name))

(defn step-to-reduce-limit-after-associating-system
  "create a system and add it to existing system group and then change
   the max-limit from 'unlimited' to '1'"
  [{:keys [group-name system-name] :as m}]
  (with-unique [system-name "test1"]
    (system/create system-name {:sockets "1"
                                :system-arch "x86_64"})
    (group/add-to group-name system-name)
    (expecting-error (common/errtype :katello.notifications/systems-exceeds-group-limit)
                     (group/edit group-name {:new-limit 1}))))
>>>>>>> master

(with-unique-ent "group" (some-group))

(defn assert-system-count
  "Assert the group g contains n systems."
  [g n]
  (assert/is (= n (group/system-count g))))

(defgroup sg-tests
  :blockers rest/katello-only
  
  (deftest "Create a system group"
    (with-unique-group g (ui/create g))

    (deftest "Copying with same system group name not allowed"
      (with-unique-group g
        (ui/create g)
        (expecting-error (common/errtype ::notif/sg-name-taken-error)
                         (group/copy g (assoc g :description "copied system group")))))

    (deftest "Edit a system group"
      :data-driven true

      (fn [f & args]
        (with-unique-group g 
          (ui/create g)
          (apply ui/update g f args)) )

      [[assoc :limit 4]
       [assoc :limit 8, :description "updated description"]
       [assoc :description "updated description"]])


    (deftest "Edit system limit of a system group, then set back to unlimited"
      (with-unique-group g
        (ui/create g)
        (-> g
            (ui/update assoc :limit 4)
            (ui/update assoc :limit :unlimited)))
      
      (deftest "System group system limit validation"
        :data-driven true

        (fn [limit pred]
          (with-unique-group g
            (ui/create g)
            (expecting-error pred (ui/update g assoc :limit limit))))

        [(with-meta
           ["-1"   (common/errtype ::notif/max-systems-must-be-positive)]
           {:blockers (open-bz-bugs "848564")})
         ["-100" (common/errtype ::notif/max-systems-must-be-positive)]
         [""     (common/errtype ::notif/max-systems-must-be-positive)]
         ["0"    (common/errtype ::notif/max-systems-may-not-be-zero)]]))


    (deftest "Add a system to a system group"
      :blockers (open-bz-bugs "845668")
      :data-driven true

      ;; Create various groups, changing the original properties if necessary
      ;; then add a system to it

      (fn [group-fn & args]
        (with-unique [s (some-system)
                      g (apply group-fn some-group args)]
          (ui/create-all (list s g))
          (ui/update g assoc :systems #{s})))
      [[identity]
       (with-meta
         [assoc :name "QE lab"]
         {:description "Add a system to a system group with a space in the group name"})])

    (deftest "Check that system count increments and decrements"
      :blockers (open-bz-bugs "857031")

      (with-unique [s (some-system)
                    g some-group]
        (ui/create-all (list s g))
        (let [g (ui/update g assoc :systems #{s})]
          (assert-system-count g 1)
          (ui/update g update-in [:systems] disj s)
          (assert-system-count g 0))))

    (deftest "Unregister a system & check count under sys-group details is -1"
      (with-unique [s1 (some-system)
                    g some-group]
        (ui/create-all (list s1 g))
        (provision/with-client "check-sys-count" ssh-conn
          (client/register ssh-conn {:username (:name *session-user*)
                                     :password (:password *session-user*)
                                     :org "ACME_Corporation"
                                     :env (-> g :env :name)
                                     :force true})
          (let [s2 (assoc (some-system) :name (client/my-hostname ssh-conn))
                g (ui/update g assoc :systems #{s1 s2})]
            (assert-system-count g 2)
            (client/sm-cmd ssh-conn :unregister)
            (assert-system-count g 1)))))

    (deftest "Delete a system group"
      :data-driven true

      (fn [data]
        (with-unique [g (merge some-group data)
                      s (some-system)]
          (ui/create-all (list s g))
          (ui/update g assoc :systems #{s})
          (ui/delete g)
          (assert/is (= (:also-remove-systems? g) (rest/not-exists? s)))))

      [[{:also-remove-systems? true}]
       [{:also-remove-systems? false}]])

    (deftest "Remove a system from copied system group"
      :blockers (open-bz-bugs "857031")
      (with-unique [g some-group
                    s (some-system)]
        (ui/create-all (list s g))
        (let [g (ui/update g assoc :systems #{s})
              clone (update-in g [:name] #(str % "-clone"))]
          (group/copy g clone)
          (ui/update clone update-in [:systems] disj s))))

    (deftest "Systems removed from System Group can be re-added to a new group"
      (with-unique [[g1 g2] some-group
                    s (some-system)]
        (ui/create-all (list g1 g2 s))
        (-> g1
            (ui/update assoc :systems #{s})
            (ui/update update-in [:systems] disj s))
        (ui/update g2 assoc :systems #{s})))

    (deftest "Reduce the max-limit after associating systems to max allowed limit"
      (with-unique [g some-group
                    [s1 s2] (some-system)]
        (ui/create-all (list g s1 s2))
        (ui/update g assoc :systems #{s1 s2})
        (expecting-error (common/errtype ::notif/systems-exceeds-group-limit)
                         (ui/update g assoc :limit 1))))

    (deftest "Add systems to sys group greater than the max allowed limit"
      (let [limit 2
            g (uniqueify some-group)
            systems (take (inc limit) (uniques (some-system)))]
        (ui/create-all (conj systems g))
        (ui/update g assoc :limit limit)
        (expecting-error (common/errtype ::notif/bulk-systems-exceeds-group-limit)
                         (system/add-bulk-sys-to-sysgrp systems g))))

    (deftest "Register a system using AK & sys count should increase by 1"
      (with-unique [g (some-group)
                    s (some-system)
                    ak (kt/newActivationKey {:name "ak", :env (:env g)})]
        (ui/create-all (list g s ak))
        (ui/update g assoc :systems #{s})
        (ui/update ak assoc :system-group g)
        (provision/with-client "sys-count" ssh-conn
          (client/register ssh-conn
                           {:org (-> ak :env :org)
                            :activationkey (:name ak)})
          (assert-system-count g 2))))

    (deftest "cancel OR close widget"
      :data-driven true
      :description "Closing the system-group widget should also close the copy widget (if its already open)
                         and 'cancel' copy widget should also work"
      (fn [opts]
        (with-unique-group g 
          (ui/create g)
          (group/cancel-close-widget g opts)))

      [[{:close-widget? true}]
       [{:close-widget? false}]])

    (deftest "Copy a system group"
      (with-unique [g some-group
                    s (some-system)]
        (ui/create-all (list g s))
        (ui/update g assoc :systems #{s})
        (group/copy g (update-in g [:name] #(str % "-clone"))))
        
      (deftest "Delete a copied system group"
        :data-driven true
        (fn [data]
<<<<<<< HEAD
          (with-unique [g (merge some-group data)
                        s (some-system)]
            (ui/create-all (list s g))
            (ui/update g assoc :systems #{s})
            (let [clone (update-in g [:name] #(str % "-clone"))]
              (group/copy g clone)
              (ui/delete clone)
              (assert/is (= (:also-remove-systems? clone) (rest/not-exists? s))))))

        [[{:also-remove-systems? true}]
         [{:also-remove-systems? false}]]))))
=======
          (do-steps (merge data (uniqueify-vals
                                 {:system-name  "mysys"
                                  :group-name "test-grp"
                                  :new-group "new-grp"}))
                    step-add-new-system
                    step-remove
                    step-add-exiting-system-to-new-group))

        [[{:also-remove-systems? false}]])

      (deftest "Reduce the max-limit after associating systems to max allowed limit"
        (do-steps (uniqueify-vals
                   {:system-name  "mysys"
                    :group-name  "copygrp"})
                  step-add-new-system
                  step-to-reduce-limit-after-associating-system))

      (deftest "Add systems to sys group greater than the max allowed limit"
        (do-steps (uniqueify-vals
                   {:system-name  "mysys"
                    :group-name  "copygrp"})
                  step-add-new-system
                  step-add-sys-to-sysgroup-with-new-limit
                  step-add-bulk-sys-to-sysgrp
                  step-add-sys-to-sysgroup-from-right-pane))

      (deftest "Register a system using AK & sys count should increase by 1"
        (with-unique [system-name "mysystem"
                      group-name "my-group"
                      key-name "auto-key"]
          (let [target-env (first *environments*)]
            (api/ensure-env-exist target-env {:prior library})
            (group/create group-name)
            (system/create system-name {:sockets "1"
                                        :system-arch "x86_64"})
            (group/add-to group-name system-name)
            (ak/create {:name key-name
                        :description "my description"
                        :environment target-env})
            (ak/associate-system-group key-name group-name)
            (let [syscount (group/system-count group-name)]
              (provision/with-client "sys-count"
                ssh-conn
                (client/register ssh-conn
                                 {:org "ACME_Corporation"
                                  :activationkey key-name})
                (assert/is (= (inc syscount) (group/system-count group-name))))))))

      (deftest "cancel OR close widget"
        :data-driven true
        :description "Closing the system-group widget should also close the copy widget (if its already open)
                         and 'cancel' copy widget should also work"
        (fn [close-widget?]
          (with-unique [group-name "copy_to_cancel"]
            (group/create group-name {:description "rh system-group"})
            (group/cancel-close-widget group-name {:close-widget? close-widget?})))

        [[{:close-widget? true}]
         [{:close-widget? false}]])

      (deftest "Copy a system group"
        (do-steps (uniqueify-vals
                   {:system-name  "mysystem"
                    :group-name  "copyme"
                    :copy-name  "imthecopy"})
                  step-add-new-system
                  step-copy)


        (deftest "Delete a copied system group"
          :data-driven true

          (fn [data]
            (do-steps (merge data (uniqueify-vals
                                   {:system-name  "mysystem"
                                    :group-name  "to-del"
                                    :copy-name  "imthecopy"}))
                      step-add-new-system
                      step-copy
                      step-remove-copy
                      step-verify-system-presence))

          [[{:also-remove-systems? true}]
           [{:also-remove-systems? false}]])))))
>>>>>>> master
