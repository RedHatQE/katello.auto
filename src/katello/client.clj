(ns katello.client
  (:require [clj-ssh.ssh :as ssh]
            [katello.conf :refer [config]]
            [slingshot.slingshot :refer [try+ throw+]]
            [clojure.string :refer [split trim]])
  (:import [java.io File]))

;;some functions to control RHSM on a remote machine via ssh

(defn build-sm-cmd [cmd & [optmap]]
  (let [collect (fn [coll] (apply str (interpose " " coll)))]
    (format "subscription-manager %s %s"
            (name cmd)
            (collect (for [[opt v] optmap]
                       (if (= v true)
                         (format "--%s" (name opt))
                         (format "--%s='%s'" (name opt) v)))))))

(defn run-cmd [session cmd]
  (ssh/ssh session {:cmd cmd}))

(defn sm-cmd
  "Runs a subscription manager command with the given options."
  [session cmd & [optmap]]
  (let [res (run-cmd session (build-sm-cmd cmd optmap))]
    (if (-> res :exit (not= 0))
      (throw+ (assoc res :type ::rhsm-error)
              "RHSM Error '%s'" (or (:err res) (:out res)))
      res)))

(defn ok? [res]
  (= 0 (:exit res)))

(defn hostname [session]
  (-> session .getSession .getHost))

(defn my-hostname "hostname according to the client itself"
  [session]
  (-> session (run-cmd "hostname") :out trim))

(defn server-hostname []
  (-> (@config :server-url) (java.net.URL.) .getHost))

(defn new-session
  ([hostname]
     (doto (ssh/session (ssh/ssh-agent {})
                        hostname
                        {:username "root", :strict-host-key-checking :no})
       (.setDaemonThread true)))) ; so jvm can exit even if connection not closed properly

(defn configure-client [session m]
  (doall (for [[heading settings] m
               [k v] settings]
           (sm-cmd :config {(keyword (str heading "." k)) v}))))

(defn setup-client [session name]
  (let [rpm-name-prefix "candlepin-cert-consumer"
        cmds [ ;; set the hostname so not all clients register with the same name
              ["echo 'HOSTNAME=%s' >> /etc/sysconfig/network" name]
              ["hostname %s" name]
              ["echo 'search `hostname -d`' >> /etc/resolv.conf"]
              
              ["yum remove -y '%s*'" rpm-name-prefix]
              ["rm -f *.rpm"]
              ["ntpdate clock.redhat.com"]
              ["wget -nd -r -l1 --no-parent -A \"*.noarch.rpm\" http://%s/pub/" (server-hostname)]
              ["rpm -ivh candlepin*.noarch.rpm"]
              
              ;; hack to install latest katello-agent. works for
              ;; latest CI build of katello ONLY. not for SAM, CFSE
              ;; etc (although it may work, it's just not testing it
              ;; correctly). The real fix for this in katello is to
              ;; make the agent available directly from katello.
              
              ["wget -O /etc/yum.repos.d/katello-agent.repo %s" (@config :agent-repo)]
              ["yum localinstall -y http://fedorapeople.org/groups/katello/releases/yum/nightly/RHEL/6Server/x86_64/katello-repos-latest.rpm http://dl.fedoraproject.org/pub/epel/6/x86_64/epel-release-6-8.noarch.rpm"]
              ["yum install -y katello-agent"]
              ["service goferd restart"]]]

    (doall (for [cmd cmds] (run-cmd session (apply format cmd))))))

(defn subscribe [session poolid]
  (sm-cmd session :subscribe {:pool poolid}))

(defn unsubscribe [session opts]
  (sm-cmd session :unsubscribe opts))

(defn register [session opts]
  (sm-cmd session :register opts))

(defn get-client-facts [session]
  (apply hash-map (split (:out (run-cmd session "subscription-manager facts --list")) #"\n|: ")))

(defn get-distro [session]
  ((get-client-facts session) "distribution.name"))

(defn get-ip-address [session]
  ((get-client-facts session) "net.interface.eth0.ipv4_address"))
