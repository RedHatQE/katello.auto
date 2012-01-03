(ns katello.tests.setup
  (:require (clojure [string :as string]
                     [data :as data])
            [fn.trace :as tr]
            (katello [tasks :as tasks]
                     [api-tasks :as api]
                     [conf :as conf]
                     [api-tasks :as api-tasks]) 
            [test.tree :as test]
            [test.tree.watcher :as watch])
  (:use [clojure.string :only [split]]
        [com.redhat.qe.auto.selenium.selenium :only [connect new-sel browser sel jquery-ajax-finished]]
        [com.redhat.qe.verify :only [check]]))

(defn new-selenium [& [single-thread]]
  (let [sel-addr (@conf/config :selenium-address)
        [host port] (split sel-addr #":")
        sel-fn (if single-thread
                 connect
                 new-sel)] 
    (sel-fn host (Integer/parseInt port) "" (@conf/config :server-url))))

(defn start-selenium []  
  (browser start)
  (browser open (@conf/config :server-url) jquery-ajax-finished)
  (tasks/login (@conf/config :admin-user) (@conf/config :admin-password)))

(defn switch-new-admin-user [user pw]
  (api/with-admin (api/create-user user {:password pw
                                         :email (str user "@myorg.org")}))
  (tasks/assign-role {:user user
                      :roles ["Administrator"]})
  (tasks/logout)
  (tasks/login user pw))

(defn stop-selenium []
  (browser stop))

(defn thread-runner [consume-fn]
  (fn []
    (binding [sel (new-selenium)
              tr/tracer (tr/per-thread-tracer tr/clj-format)
              katello.conf/*session-user* (tasks/uniqueify
                                           (str (@conf/config :admin-user)
                                                (->> (Thread/currentThread)
                                                   .getName
                                                   (re-seq #"\d+")
                                                   first)))]
      (tr/dotrace-all
       {:namespaces [katello.tasks katello.api-tasks]
        :fns [test/execute
              start-selenium stop-selenium switch-new-admin-user
              check
              com.redhat.qe.auto.selenium.selenium/call-sel]
        :exclude [katello.tasks/notification
                  katello.tasks/clear-all-notifications
                  katello.tasks/success?
                  katello.tasks/uniqueify
                  katello.tasks/unique-names
                  katello.tasks/timestamps]} ;;don't try to trace a fn that returns lazy infinite seq!
       (println "starting a selenium session.")
       (try
         ;;remove this ssl workaround when able to use CA properly
         (com.redhat.qe.tools.SSLCertificateTruster/trustAllCerts)
         (com.redhat.qe.tools.SSLCertificateTruster/trustAllCertsForApacheXMLRPC)

         (start-selenium)
         (switch-new-admin-user conf/*session-user* conf/*session-password*)
         (catch Exception e (.printStackTrace e)))
       (consume-fn)
       (stop-selenium)
       (tr/htmlify "html" [(str (.getName (Thread/currentThread)) ".trace")]
                   "http://hudson.rhq.lab.eng.bos.redhat.com:8080/shared/syntaxhighlighter/")))))

(def runner-config 
  {:thread-runner thread-runner
   :setup (fn [] (println "initializing.") (conf/init))
   :watchers {:stdout-log (fn [k r o n]
                            (let [[_ d _] (data/diff o n)]
                              (doseq [[{:keys [name parameters]} {:keys [status report]}] d]
                                (let [parms-str (if parameters (pr-str parameters) "")]
                                  (if (= status :done)
                                         (println (str (:result report) ": " name parms-str) )
                                         (println (str status ": " name parms-str)))))))
              :screencapture (watch/on-fail
                              (fn [t _] 
                                (browser "screenCapture"
                                                  "screenshots"
                                                  (str 
                                                   (:name t)
                                                   (if (:parameters t)
                                                     (str "-" (string/replace (pr-str (:parameters t)) "/" "\\"))
                                                     "")
                                                   ".png")
                                                  false)))}})
