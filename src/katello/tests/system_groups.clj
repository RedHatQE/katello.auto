(ns katello.tests.system-groups
  (:refer-clojure :exclude [fn])
  (:require [katello :as kt]
            (katello [ui :as ui]
                     [rest :as rest]
                     [api-tasks :as api]
;                     [organizations :as org]
                     [activation-keys :as ak]
                     [client :as client]
                     [ui-common :as common]
                     [tasks :refer [uniqueify with-unique]]
                     [systems :as system]
                     [system-groups :as group]
                     [conf :refer [*session-user* config *environments*]])
            [katello.client.provision :as provision]
            
            (test.tree [script :refer [defgroup deftest]]
                       [builder :refer [union]])
            [serializable.fn :refer [fn]]
            [test.assert :as assert]
            [bugzilla.checker :refer [open-bz-bugs]]))

(alias 'notif 'katello.notifications)
;; Functions

(def basegroup (kt/newSystemGroup {:name "group" :env (first *environments*)}))
(def basesystem (kt/newSystem {:name "system" :env (first *environments*)}))

(def ^{:doc "an infinite series of unique system groups"}
  unique-groups
  (uniques basegroup))

(defmacro with-unique-group [sym & body]
  `(with-unique [~sym basegroup]
     ~@body))

(defn assert-system-count
  "Assert the group g contains n systems."
  [g n]
  (assert/is (= n (group/system-count g))))

(defgroup sg-tests
  :blockers api/katello-only
  :group-setup #(api/ensure-env-exist "dev" {:prior "Library"})
  :test-setup org/before-test-switch

  (deftest "Create a system group"
    (->> unique-groups (take 1) ui/create)

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
        (with-unique [s basesystem
                      g (apply group-fn basegroup args)]
          (ui/create-all (list s g))
          (ui/update g assoc :systems #{s})))
      [[identity]
       (with-meta
         [assoc :name "QE lab"]
         {:description "Add a system to a system group with a space in the group name"})])

    (deftest "Check that system count increments and decrements"
      :blockers (open-bz-bugs "857031")

      (with-unique [s basesystem
                    g basegroup]
        (ui/create-all (list s g))
        (let [g (ui/update g assoc :systems #{s})]
          (assert-system-count g 1)
          (ui/update g update-in [:systems] dissoc s)
          (assert-system-count g 0))))

    (deftest "Unregister a system & check count under sys-group details is -1"
      (with-unique [s1 basesystem
                    g basegroup]
        (ui/create-all (list s1 g))
        (provision/with-client "check-sys-count" ssh-conn
          (client/register ssh-conn {:username (:name *session-user*)
                                     :password (:password *session-user*)
                                     :org "ACME_Corporation"
                                     :env (-> g :env :name)
                                     :force true})
          (let [s2 (assoc basesystem :name (client/my-hostname ssh-conn))
                g (ui/update g assoc :systems #{s1 s2})]
            (assert-system-count g 2)
            (client/sm-cmd ssh-conn :unregister)
            (assert-system-count g 1)))))

    (deftest "Delete a system group"
      :data-driven true

      (fn [data]
        (with-unique [g (merge basegroup data)
                      s basesystem]
          (ui/create-all (list s g))
          (ui/update g assoc :systems #{s})
          (ui/delete g)
          (assert/is (= (:also-remove-systems? g) (rest/not-exists? s)))))

      [[{:also-remove-systems? true}]
       [{:also-remove-systems? false}]])

    (deftest "Remove a system from copied system group"
      :blockers (open-bz-bugs "857031")
      (with-unique [g basegroup
                    s basesystem]
        (ui/create-all (list s g))
        (let [g (ui/update g assoc :systems #{s})
              clone (update-in g [:name] #(str % "-clone"))]
          (group/copy g clone)
          (ui/update clone update-in [:systems] dissoc s))))

    (deftest "Systems removed from System Group can be re-added to a new group"
      (with-unique [g1 basegroup
                    g2 basegroup
                    s basesystem]
        (ui/create-all (list g1 g2 s))
        (-> g1
            (ui/update assoc :systems #{s})
            (ui/update update-in [:systems] dissoc s))
        (ui/update g2 assoc :systems #{s})))

    (deftest "Reduce the max-limit after associating systems to max allowed limit"
      (with-unique [g basegroup
                    s1 basesystem
                    s2 basesystem]
        (ui/create-all (list g s1 s2))
        (ui/update g assoc :systems #{s1 s2})
        (expecting-error (common/errtype ::notif/systems-exceeds-group-limit)
                         (ui/update g assoc :limit 1))))

    (deftest "Add systems to sys group greater than the max allowed limit"
      (let [limit 2
            g (uniqueify basegroup)
            systems (take (inc limit) (uniques basesystem))]
        (ui/create-all (conj systems g))
        (ui/update g assoc :limit limit)
        (expecting-error (common/errtype ::notif/bulk-systems-exceeds-group-limit)
                         (ui/update g assoc :systems (apply hash-set systems)))) )

    (deftest "Register a system using AK & sys count should increase by 1"
      (with-unique [g basegroup
                    s basesystem
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
      (with-unique [g basegroup
                    s basesystem]
        (ui/create-all (list g s))
        (ui/update g assoc :systems #{s})
        (group/copy g (update-in g [:name] #(str % "-clone"))))
        
      (deftest "Delete a copied system group"
        :data-driven true
        (fn [data]
          (with-unique [g (merge basegroup data)
                        s basesystem]
            (ui/create-all (list s g))
            (ui/update g assoc :systems #{s})
            (let [clone (update-in g [:name] #(str % "-clone"))]
              (group/copy g clone)
              (ui/delete clone)
              (assert/is (= (:also-remove-systems? clone) (rest/not-exists? s))))))

        [[{:also-remove-systems? true}]
         [{:also-remove-systems? false}]]))))
