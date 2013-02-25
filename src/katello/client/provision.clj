(ns katello.client.provision
  (:require [deltacloud :as cloud]           
            (katello [client :as client]
                     [conf :as conf])))


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
