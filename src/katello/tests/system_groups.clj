(ns katello.tests.system-groups
  (:refer-clojure :exclude [fn])
  (:require (katello [api-tasks :as api]
                     [organizations :as org]
                     [activation-keys :as ak]
                     [client :as client]
                     [ui-common :as common]
                     [tasks :refer :all]
                     [systems :as system]
                     [system-groups :as group]
                     [conf :refer [*session-user* *session-password* config *environments*]])
            [katello.client.provision :as provision]
            [katello.tests.systems]
            (test.tree [script :refer [defgroup deftest]]
                       [builder :refer [union]])
            [serializable.fn :refer [fn]]
            [test.assert :as assert]
            [bugzilla.checker :refer [open-bz-bugs]]))

;; Functions

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


(defgroup sg-tests
  :blockers api/katello-only
  :group-setup #(api/ensure-env-exist "dev" {:prior "Library"})
  :test-setup org/before-test-switch

  (deftest "Create a system group"
    (with-unique [group-name "fed"]
      (group/create group-name {:description "rh system-group"}))

    (deftest "Copying with similar sg-name not allowed"
      (with-unique [group-name "fed1"]
        (group/create group-name {:description "rh system-group"})
        (expecting-error (common/errtype :katello.notifications/sg-name-taken-error)
                         (group/copy group-name group-name {:description "copied system group"}))))

    (deftest "Edit a system group"
      :data-driven true

      (fn [data]
        (do-steps (merge data
                         (uniqueify-vals {:group-name "sg"}))
                  step-create
                  step-edit))

      [[{:group-new-limit 4}]
       [{:group-new-limit 8
         :group-new-description "updated description"}]
       [{:group-new-description "updated description"}]])


    (deftest "Edit system limit of a system group"
      (with-unique [group-name "sg"]
        (group/create group-name)
        (group/edit group-name {:new-limit 4}))


      (deftest "Edit system limit of a system group, then set back to unlimited"
        (with-unique [group-name "sg"]
          (group/create group-name)
          (group/edit group-name {:new-limit 4})
          (group/edit group-name {:new-limit :unlimited}))


        (deftest "System group system limit validation"
          :data-driven true

          (fn [limit pred]
            (with-unique [group-name "sg-val"]
              (group/create group-name)
              (expecting-error pred (group/edit
                                     group-name {:new-limit limit}))))

          [(with-meta
             ["-1"   (common/errtype :katello.notifications/max-systems-must-be-positive)]
             {:blockers (open-bz-bugs "848564")})
           ["-100" (common/errtype :katello.notifications/max-systems-must-be-positive)]
           [""     (common/errtype :katello.notifications/max-systems-must-be-positive)]
           ["0"    (common/errtype :katello.notifications/max-systems-may-not-be-zero)]])))


    (deftest "Add a system to a system group"
      :blockers (open-bz-bugs "845668")
      (do-steps (uniqueify-vals
                 {:system-name "mysystem"
                  :group-name "my-group"})
                step-add-new-system)

      (deftest "Add a system to a system group with a space in the group name"
        :blockers (open-bz-bugs "845668")
        (do-steps (uniqueify-vals
                   {:system-name "mysystem"
                    :group-name "QE lab"})
                  step-add-new-system))

      (deftest "Add a system to a system group and check count is +1"
        (with-unique [system-name "mysystem"
                      group-name "my-group"]
          (do
            (group/create group-name)
            (let [syscount  (group/system-count group-name)]
              (system/create system-name {:sockets "1"
                                          :system-arch "x86_64"})
              (group/add-to group-name system-name)
              (assert/is (= (inc syscount) (group/system-count group-name)))))))

      (deftest "Remove a system from a system group and check count is -1"
        :blockers (open-bz-bugs "857031")
        (with-unique [system-name "mysystem"
                      group-name "my-group"]
          (do
            (group/create group-name)
            (system/create system-name {:sockets "1"
                                        :system-arch "x86_64"})
            (group/add-to group-name system-name)
            (let [syscount  (group/system-count group-name)]
              (group/remove-from group-name system-name)
              (assert/is (= (dec syscount) (group/system-count group-name)))))))

      (deftest "Unregister a system & check count under sys-group details is -1"
        (with-unique [system-name "mysystem"
                      group-name "my-group"]
          (let [target-env (first *environments*)]
            (api/ensure-env-exist target-env {:prior library})
            (do
              (group/create group-name)
              (system/create system-name {:sockets "1"
                                          :system-arch "x86_64"})
              (group/add-to group-name system-name)
              (provision/with-client "check-sys-count"
                ssh-conn
                (client/register ssh-conn
                                 {:username *session-user*
                                  :password *session-password*
                                  :org "ACME_Corporation"
                                  :env target-env
                                  :force true})
                (let [mysys (client/my-hostname ssh-conn)]
                  (group/add-to group-name mysys)
                  (let [syscount (group/system-count group-name)]
                    (client/sm-cmd ssh-conn :unregister)
                    (assert/is (= (dec syscount) (group/system-count group-name))))))))))

      (deftest "Delete a system group"
        :data-driven true

        (fn [data]
          (do-steps (merge data
                           (uniqueify-vals {:system-name "mysystem"
                                            :group-name "to-del"}))
                    step-add-new-system
                    step-remove
                    step-verify-system-presence))

        [[{:also-remove-systems? true}]
         [{:also-remove-systems? false}]])

      (deftest "Remove a system from copied system group"
        :blockers (open-bz-bugs "857031")
        (do-steps (uniqueify-vals
                   {:system-name  "mysys"
                    :group-name  "copygrp"
                    :copy-name  "copy_mysys"})
                  step-add-new-system
                  step-copy
                  step-remove-sys-from-copied-system-group))

      (deftest "Systems removed from System Group can be re-added to a new group"
        :data-driven true
        (fn [data]
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
