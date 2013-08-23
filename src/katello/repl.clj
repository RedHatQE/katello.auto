(ns katello.repl
  (:require [clojure.pprint :refer [pp pprint]]
            fn.trace
            [clj-webdriver.taxi :as taxi]
            katello.conf
            katello.setup
            test.tree.debug))


(defn new-browser [& [optmap]]
  (katello.setup/start-selenium))


;;-------------

(defmacro trace [& body]
  `(fn.trace/dotrace-depth (katello.conf/trace-list)
     ~@body))

(defn debug [tree]
  (test.tree.debug/debug tree {:trace-depth-map (katello.conf/trace-list)}))

(defn print-name-result [resulttree]
  (doseq [result @(second resulttree)]
    (println 
      (:name(first result)) 
      (:parameters (deref (:report (second result))))
      (:result (deref (:report (second result)))))))

(defn name-result [resulttree]
  (for [result @(second resulttree)]
    [(:name(first result)) 
     (:parameters (:report (second result)))
     (:result (:report (second result)))]))


(defn start-session []
  (katello.conf/init {:selenium-address "localhost:4444"})


  (if-let [locale (@katello.conf/config :locale)]
    (do (new-browser {:browser-config-opts (katello.setup/config-with-profile
                                            locale)}))
    (new-browser)))

(defmacro with-n-browsers [n & body]
  `(map deref
        (doall (for [_# (range ~n)]
                 (future (binding [sel/sel (katello.setup/new-selenium "*firefox")]
                           (katello.setup/start-selenium)
                           ~@body))))))



