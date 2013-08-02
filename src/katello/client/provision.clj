(ns katello.client.provision
  (:require [ovirt.client :as ovirt]
            [slingshot.slingshot :refer [throw+ try+]]
            (katello [client :as client]
                     [conf :as conf]))
  (:import [java.util.concurrent ArrayBlockingQueue TimeUnit]
           [org.ovirt.engine.sdk.entities VM]))

(defrecord Queue [queue shutdown?])
(defrecord Client [vm ssh-connection])

(defonce queue (atom nil))

(defn new-queue
  "Create a new instance queue with given capacity."
  [capacity]
  (Queue. (ArrayBlockingQueue. capacity) nil))

(defn- clean-up-queue [q]
  (let [leftovers (java.util.ArrayList.)]
    (.drainTo q leftovers)
    (ovirt/unprovision-all (->> leftovers
                                (map deref)
                                (take-while #(not= :end %))
                                (map :vm)))))

(defn add-ssh
  "Add ssh command runner field to the given instance,returning a
  Client record."
  [vm]
  (try
    (->Client vm (-> vm ovirt/ip-address client/new-runner))
    (catch Exception e
      (map->Client {:vm vm
                    :ssh-connection-error e}))))

(defn- provision
  "Provision a client from ovirt and set it up as a
   client (including installation of rpm to configure rhsm)"
  [def]
  (let [client (->> def
                    (ovirt/provision (:api conf/*cloud-conn*)
                                     (:cluster conf/*cloud-conn*))
                    add-ssh)]
    (client/setup-client (:ssh-connection client) (-> client :vm .getName))
    client))

(defn fill-queue
  "Continuously backfill the queue (qa should be an atom containing a
  Queue), will block indefinitely until the atom is updated with the
  field :shutdown? set to true.  At that point, the queue will be
  drained and all instances in it unprovisioned.  This function should
  probably be called with future, because it blocks."
  [qa]
  (loop [defs (conf/client-defs "pre-provision"), p (promise)]  
    (let [shutdown? (:shutdown? @qa)
          accepted? (.offer (:queue @qa) p 5 TimeUnit/SECONDS)]
      (cond shutdown? (do (deliver p :end) ; avoid undelivered promise
                          (clean-up-queue (:queue @qa)))
            accepted? (do (future (try+
                                    (->> defs first provision (deliver p))
                                    (catch Object o
                                      (deliver p o))))
                          (recur (rest defs) (promise))) ; new promise
            :else (recur defs p)) ; recur with same args if queue is full
      )))

(defn dequeue
  "Takes a Queue and returns the next Client from it, checking for
  errors and throwing them if they occurred."
  [q]
  (let [obj (-> q :queue .poll (deref 600000 {:type ::dequeue-timeout}))]
    (if (instance? Client obj)
      obj
      (throw+ obj))))

(defmacro with-queued-client
  [ssh-conn-bind & body]
  `(let [client# (-> queue
                   deref                ; the atom
                   dequeue) 
         ~ssh-conn-bind (:ssh-connection client#)]
     (try ~@body
          (finally
            (when ovirt/*kill-instance-when-finished*
              (future (ovirt/unprovision (:vm client#))))))))

(defn init "Initialize the queue to n items" [n]
  (reset! queue (new-queue n))

  (future (fill-queue queue)))

(defn shutdown
  "Stop all remaining instances in the queue, takes the future
  returned by init.  Blocks until all cleanup is done."
  [f]
  (swap! queue assoc :shutdown? true)
  (deref f))
