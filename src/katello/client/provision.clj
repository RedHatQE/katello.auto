(ns katello.client.provision
  (:require [deltacloud :as cloud]
            [slingshot.slingshot :refer [throw+]]
            (katello [client :as client]
                     [conf :as conf]))
  (:import [java.util.concurrent ArrayBlockingQueue TimeUnit]))

(defrecord Queue [queue shutdown?])

(defonce queue (atom nil))

(defn new-queue
  "Create a new instance queue with given capacity."
  [capacity]
  (Queue. (ArrayBlockingQueue. capacity) nil))

(defn- clean-up-queue [q]
  (let [leftovers (java.util.ArrayList.)]
    (.drainTo q leftovers)
    (cloud/unprovision-all (->> leftovers
                                (map deref)
                                (take-while #(not= :end %))))))

(defn- add-ssh "Add ssh command runner field to the given instance."
  [inst]
  (try
    (assoc inst :ssh-connection
           (client/new-runner (cloud/ip-address inst)))
    (catch Exception e
      (assoc inst :ssh-connection-error e))))

(defn- provision
  "Provision a client from deltacloud and set it up as a
   client (including installation of rpm to configure rhsm)"
  [def]
  (let [inst (->> def
                  (cloud/provision conf/*cloud-conn*)
                  add-ssh)]
    (client/setup-client (:ssh-connection inst) (:name inst))
    inst))

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
  "Takes a Queue and returns the next instance from it, checking for
  errors and throwing them if they occurred."
  [q]
  (let [inst (-> q :queue .poll (deref 600000 {:type ::dequeue-timeout}))]
    (if (instance? deltacloud.Instance inst)
      inst
      (throw+ inst))))

(defmacro with-n-clients
  "Provisions n clients with instance name basename (plus unique
 identifier) and configures them for the katello server under test.
 inst-bind is a symbol or destructuring form to refer to the instance
 data returned from the cloud provider. n is the number of instances
 to provision. Runs body and then terminates the instances (even if
 there was an exception thrown) eg.
   (with-n-clients 2 \"myinstname\" [c1 c2] ...)"
  [n clientname ssh-conns-bind & body]
  `(cloud/with-instances
     [inst# (->> (conf/client-defs ~clientname)
               (take ~n)
               (cloud/provision-all conf/*cloud-conn*)
               :instances
               (pmap add-ssh))]
     (let [~ssh-conns-bind (do (doall
                                (pmap 
                                 (comp client/setup-client :ssh-connection) inst#))
                               (map :ssh-connection inst#))]
       ~@body)))

(defmacro with-client
  "Provisions a client with instance name clientname (plus unique
 identifier) and configures it for the katello server under test.
 ssh-conn-bind will be bound to an SSHCommandRunner to run commands on
 the client. Executes body and then terminates the instances (even if
 there was an exception thrown). sample:
   (with-client \"myinstname\" client (do-thing client))"
  [clientname ssh-conn-bind & body]
  `(cloud/with-instance
       [inst# (->> (conf/client-defs ~clientname)
                 first
                 (cloud/provision conf/*cloud-conn*)
                 add-ssh)]
     
     (let [~ssh-conn-bind (:ssh-connection inst#)]
       (client/setup-client ~ssh-conn-bind (:name inst#))
       ~@body)))

(defmacro with-queued-client
  [ssh-conn-bind & body]
  `(let [inst# (-> queue
                   deref                ; the atom
                   dequeue) 
         ~ssh-conn-bind (:ssh-connection inst#)]
     (try ~@body
          (finally
            (when cloud/*kill-instance-when-finished*
              (future (cloud/unprovision inst#)))))))

(defn init "Initialize the queue to n items" [n]
  (reset! queue (new-queue n))
  (future (fill-queue queue)))

(defn shutdown
  "Stop all remaining instances in the queue, takes the future
  returned by init.  Blocks until all cleanup is done."
  [f]
  (swap! queue assoc :shutdown? true)
  (deref f))
