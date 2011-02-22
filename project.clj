(defproject kalpana "1.0.0-SNAPSHOT"
  :description "Kalpana automation"
  :aot [#"^kalpana.tests"]
  :dependencies [[org.clojure/clojure "1.2.0"]
                 [org.clojure/clojure-contrib "1.2.0"]
                 [webui-framework "1.0.1-SNAPSHOT"]
                 [test_clj.testng "1.0.0-SNAPSHOT"]
                 [error.handler "1.0.0-SNAPSHOT"]]
  :dev-dependencies [[swank-clojure "1.2.1"]])

(comment "Execute this in the repl to load everything and start selenium"
         (do (require 'kalpana.tasks :reload-all)
             (require 'kalpana.conf)
             (require 'kalpana.tests.setup)
             (kalpana.conf/init)
             (kalpana.tests.setup/start_selenium nil)
             (kalpana.tasks/login "admin" "admin"))
         )


(comment
  (ns leiningen.develop
  (:use [leiningen.compile :only [eval-in-project]])
  (:require leiningen.swank))
(defn develop [project & args]
  (println "Starting dev env")
  (apply leiningen.swank/swank project args)
  (println "starting custom stuff")
  (eval-in-project project
                   '(do (require 'kalpana.tasks :reload-all)
                        (require 'kalpana.conf)
                        (require 'kalpana.tests.login-tests)
                        (kalpana.conf/init)
                        (kalpana.tests.setup/start_selenium nil)
                        (kalpana.tasks/login "admin" "admin")))))
