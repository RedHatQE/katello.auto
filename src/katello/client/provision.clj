(ns katello.client.provision
  (:require [deltacloud :as cloud]           
            (katello [client :as client]
                     [conf :as conf])))


(defn add-ssh [inst]
  (assoc inst :ssh-connection (client/new-runner (cloud/ip-address inst))))

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
       [inst# (update-in (->> (conf/client-defs ~clientname)
                            (take ~n)
                            (map (partial cloud/provision-all conf/*cloud-conn*))
                            (map add-ssh))
                         [:instances]
                         (partial map-ssh))]
     (let [~ssh-conns-bind (doall
                            (for [i# inst#]
                              (client/setup-client (:ssh-connection i#))))]
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
       (client/setup-client ~ssh-conn-bind)
       ~@body)))