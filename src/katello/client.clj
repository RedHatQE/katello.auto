(ns katello.client
  (:require (katello [api-tasks :as api]))
  (:require [katello.conf :refer [config]]
            [slingshot.slingshot :refer [try+ throw+]]
            [clojure.string :refer [split trim]])
  (:import [com.redhat.qe.tools SSHCommandRunner]
           [java.io File]))

;;some functions to control RHSM on a remote machine via ssh

(defn build-sm-cmd [cmd & [optmap]]
  (let [collect (fn [coll] (apply str (interpose " " coll)))]
    (format "subscription-manager %s %s"
            (name cmd)
            (collect (for [[opt v] optmap]
                       (if (= v true)
                         (format "--%s" (name opt))
                         (format "--%s='%s'" (name opt) v)))))))

(defn run-cmd [runner cmd]
  (let [result (.runCommandAndWait runner cmd)]
    {:stdout (.getStdout result)
     :stderr (.getStderr result)
     :exit-code (.getExitCode result)}))

(defn sm-cmd
  "Runs a subscription manager command with the given options."
  [runner cmd & [optmap]]
  (let [res (run-cmd runner (build-sm-cmd cmd optmap))]
    (if (-> res :exit-code (not= 0))
      (throw+ (assoc res :type ::rhsm-error)
              "RHSM Error '%s'" (if (-> res :stderr count (> 0))
                                  (:stderr res)
                                  (:stdout res)))
      res)))

(defn ok? [res]
  (= 0 (:exit-code res)))

(defn hostname [runner]
  (-> runner .getConnection .getHostname))

(defn my-hostname "hostname according to the client itself"
  [runner]
  (-> runner (run-cmd "hostname") :stdout trim))

(defn server-hostname []
  (-> (@config :server-url) (java.net.URL.) .getHost))

(defn new-runner
  ([hostname]
     (SSHCommandRunner. hostname "root"
                        (File. ^String (@config :client-ssh-key))
                        (@config :client-ssh-key-passphrase) nil))
  ([hostname user password keyfile keypassphrase]
     (SSHCommandRunner. hostname user (File. ^String keyfile) keypassphrase nil)))

(defn configure-client [runner m]
  (doall (for [[heading settings] m
               [k v] settings]
           (sm-cmd :config {(keyword (str heading "." k)) v}))))

(defn setup-client [runner]
  (let [rpm-name-prefix "candlepin-cert-consumer"
        cmds [["subscription-manager clean"] 
              ["yum remove -y '%s*'" rpm-name-prefix]
              ["rm -f *.rpm"]
              ["wget -nd -r -l1 --no-parent -A \"*.noarch.rpm\" http://%s/pub/" (server-hostname)]
              ["rpm -ivh candlepin*.noarch.rpm"]
              
              ;; hack to install latest katello-agent. works for
              ;; latest CI build of katello ONLY. not for SAM, CFSE
              ;; etc (although it may work, it's just not testing it
              ;; correctly). The real fix for this in katello is to
              ;; make the agent available directly from katello.
              
              ["wget -O /etc/yum.repos.d/katello-agent.repo %s" (@config :agent-repo)]
              ["yum localinstall -y http://fedorapeople.org/groups/katello/releases/yum/nightly/RHEL/6Server/x86_64/katello-repos-latest.rpm http://dl.fedoraproject.org/pub/epel/6/x86_64/epel-release-6-7.noarch.rpm"]
              ["yum install -y katello-agent"]]]
    (doall (for [cmd cmds] (run-cmd runner (apply format cmd))))))

(defn subscribe [runner poolid]
  (sm-cmd runner :subscribe {:pool poolid}))

(defn register [runner opts]
  (sm-cmd runner :register opts))

(defn get-client-facts [runner]
  (apply hash-map (split (:stdout (run-cmd runner "subscription-manager facts --list")) #"\n|: ")))

(defn get-distro [runner]
  ((get-client-facts runner) "distribution.name"))

(defn get-pool-id "Fetch subscription pool-id"
  [mysys product-name]
  (let [pool-provides-product (fn [prod pool]
                                (or (= (:productName pool) prod)
                                    (some #(= (:productName %) prod)
                                          (:providedProducts pool))))]
    (->> (api/system-available-pools mysys)
      (filter (partial pool-provides-product product-name))
      first :id)))