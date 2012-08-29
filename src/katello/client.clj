(ns katello.client
  (:require [katello.conf :refer [config]]
            [slingshot.slingshot :refer [try+ throw+]])
  (:import [com.redhat.qe.tools SSHCommandRunner]
           [java.io File]))

;;some functions to control RHSM on a remote machine via ssh

(def script (atom nil))

(declare  ^:dynamic *runner*)

(defn build-sm-cmd [cmd & [optmap]]
  (let [collect (fn [coll] (apply str (interpose " " coll)))]
    (format "subscription-manager %s %s"
            (name cmd)
            (collect (for [[opt v] optmap]
                       (if (= v true)
                         (format "--%s" (name opt))
                         (format "--%s='%s'" (name opt) v)))))))

(defn run-cmd [cmd]
  (let [result (.runCommandAndWait *runner* cmd)]
    {:stdout (.getStdout result)
     :stderr (.getStderr result)
     :exit-code (.getExitCode result)}))

(defn sm-cmd
  "Runs a subscription manager command with the given options."
  [cmd & [optmap]]
  (let [res (run-cmd (build-sm-cmd cmd optmap))]
    (if (-> res :exit-code (not= 0))
      (throw+ (assoc res :type ::rhsm-error)
              "RHSM Error '%s'" (if (-> res :stderr count (> 0))
                                  (:stderr res)
                                  (:stdout res)))
      res)))

(defn ok? [res]
  (= 0 (:exit-code res)))

(defn server-hostname []
  (-> (@config :server-url) (java.net.URL.) .getHost))

(defn new-runner [hostname user password keyfile keypassphrase]
  (SSHCommandRunner. hostname user (File. ^String keyfile) keypassphrase nil))

(defn connect [runner]
  (def ^:dynamic *runner* runner))

(defn configure-client [m]
  (doall (for [[heading settings] m
               [k v] settings]
           (sm-cmd :config {(keyword (str heading "." k)) v}))))

(defn setup-client []
  (let [rpm-name-prefix "candlepin-cert-consumer"
        cmds [["subscription-manager clean"] 
              ["yum remove -y '%s*'" rpm-name-prefix]
              ["rpm -ivh http://%1$s/pub/%2$s-%1$s-1.0-1.noarch.rpm" (server-hostname) rpm-name-prefix]]]
    (doall (for [cmd cmds] (run-cmd (apply format cmd))))))

(defn subscribe [poolid]
  (sm-cmd :subscribe {:pool poolid}))

(defn register [opts]
  (sm-cmd :register opts))

(comment (def ^:dynamic *runner*
           (SSHCommandRunner. "katello-client1.usersys.redhat.com"
                              "root"
                              (File. "/home/jweiss/workspace/automatjon/sm/.ssh/id_auto_dsa")
                              "dog8code" nil)))
