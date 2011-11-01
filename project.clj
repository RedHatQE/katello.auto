(defproject katello "1.0.0-SNAPSHOT"
  :description "Katello automation"  
  :main katello.tests.suite
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [org.clojure/data.json "0.1.1"]
                 [webui-framework "1.0.2-SNAPSHOT"]
                 [test.tree "0.5.0-SNAPSHOT"]
                 [slingshot "0.8.0"]
                 [inflections "0.6.2"]
                 [clj-http "0.1.3"]
                 [fn.trace "1.3.0-SNAPSHOT"]]
  :jvm-opts ["-Xmx256m"]
  :repositories {"clojars" {:url "http://clojars.org/repo"
                            :snapshots {:update :always}}})

(comment "Execute this in the repl to load everything and start selenium"
         (do
           (do (require 'katello.tasks :reload-all)
               (require 'katello.conf :reload)
               (require 'katello.tests.setup :reload)
                 
               (katello.conf/init))  ;;<-here for api only
           (katello.tests.setup/new-selenium true)
           (katello.tests.setup/start-selenium))   ;;<-here for selenium
         )
