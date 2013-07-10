(ns katello.client.provision
  (:require [deltacloud :as cloud]
            [slingshot.slingshot :refer [throw+]]
            (katello [client :as client]
                     [conf :as conf]))
  (:import [java.util.concurrent ArrayBlockingQueue TimeUnit]))

(defrecord Queue [queue shutdown?])

(defonce queue (atom nil))

(defn new-queue [capacity]
  (Queue. (ArrayBlockingQueue. capacity) nil))

(defn- clean-up-queue [q]
  (let [leftovers (java.util.ArrayList.)]
    (-> q (.drainTo leftovers))
    (let [agents (map deref leftovers)]
      (doseq [agnt agents]
        (send agnt cloud/unprovision))
      (when-not (apply await-for 600000 agents)
        (throw+ {:type ::cleanup-timeout
                 ::leftover-instance-agents agents})))))

(defn fill-queue
  [qa]
  (loop [defs (conf/client-defs "pre-provision"), p (promise)]  
    (let [shutdown? (:shutdown? @qa)
          accepted? (.offer (:queue @qa) p 5 TimeUnit/SECONDS)]
      (cond shutdown? (do (deliver p (cloud/map->Instance {})) ; avoid undelivered promise
                          (clean-up-queue (:queue @qa)))
            accepted? (do (future (try
                                    (->> defs
                                         first
                                         (cloud/provision conf/*cloud-conn*)
                                         agent
                                         (deliver p))
                                    (catch Exception e
                                      (deliver p e))))
                          (recur (rest defs) (promise))) ; new promise
            :else (recur defs p)) ; recur with same args if queue is full
      )))

(defn dequeue
  "Takes a Queue and returns the next instance from it, checking for
  errors and throwing them if they occurred."
  [q]
  (let [agnt (-> q :queue .poll deref)]
    (if (instance? Throwable agnt)
      (throw+ agnt)
      agnt)))

(defn add-ssh [inst]
  (try
    (assoc inst :ssh-connection
           (client/new-runner (cloud/ip-address inst)))
    (catch Exception e
      (assoc inst :ssh-connection-error e))))



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
  `(let [agnt# (-> queue
                   deref                ; the atom
                   dequeue) 
         ~ssh-conn-bind (client/new-runner (cloud/ip-address (deref agnt#)))]
     (client/setup-client ~ssh-conn-bind (-> agnt# deref :name))
     (try ~@body
          (finally
            (when cloud/*kill-instance-when-finished*
              (send agnt# cloud/unprovision))))))

(defn init "Initialize the queue to n items" [n]
  (reset! queue (new-queue n))
  (future (fill-queue queue)))

(defn shutdown
  "Stop all remaining instances in the queue, takes the future
  returned by init.  Blocks until all cleanup is done."
  [f]
  (swap! queue assoc :shutdown? true)
  (deref f))
