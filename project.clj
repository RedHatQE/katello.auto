(defproject kalpana "1.0.0-SNAPSHOT"
  :description "Kalpana automation"
  :aot [#"^kalpana.tests"]
  :dependencies [[org.clojure/clojure "1.2.0"]
                 [org.clojure/clojure-contrib "1.2.0"]
                 [webui-framework "1.0.2-SNAPSHOT"]
                 [test_clj.testng "1.0.0-SNAPSHOT"]
                 [error.handler "1.1.0-SNAPSHOT"]
                 [inflections "0.4"]
                 [clj-http "0.1.1"]]
  :dev-dependencies [[swank-clojure "[1.2.1,)"]])

(comment "Execute this in the repl to load everything and start selenium"
         (do (require 'kalpana.tasks :reload-all)
             (require 'kalpana.conf :reload)
             (require 'kalpana.tests.setup)
             (kalpana.conf/init)
             (kalpana.tests.setup/start_selenium nil)
             (kalpana.tasks/login "admin" "admin"))
         )

