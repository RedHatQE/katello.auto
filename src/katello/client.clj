(ns katello.client
  (:use [katello.conf :only [config]]
        [slingshot.slingshot :only [try+ throw+]])
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
  (for [[heading settings] m
        [k v] settings]
    (sm-cmd :config {(keyword (str heading "." k)) v})))

(defn get-ca-certs []
  (let [certs {"candlepin-ca.crt" "candlepin-local.pem"
               "candlepin-upstream-ca.crt" "candlepin-upstream-local.pem"}
        host (server-hostname)]
    (doseq [[rem loc] certs]
      (run-cmd
       (format "wget -O /etc/rhsm/ca/%s http://%s/pub/%s" loc host rem)))))

(defn setup-client []
  (let [hostname  (server-hostname)]
    (get-ca-certs)
    (configure-client {"server" {"port" "443"
                                 "hostname" hostname
                                 "prefix" "/katello/api"}
                       "rhsm" {"baseurl" (format "https://%s/pulp/repos" hostname)
                               "repo_ca_cert" "%(ca_cert_dir)scandlepin-local.pem"}})))

(defn subscribe [poolid]
  (sm-cmd :subscribe {:pool poolid}))

(defn register [opts]
  (sm-cmd :register opts))

(comment (def ^:dynamic *runner*
           (SSHCommandRunner. "katello-client1.usersys.redhat.com"
                              "root"
                              (File. "/home/jweiss/workspace/automatjon/sm/.ssh/id_auto_dsa")
                              "dog8code" nil)))