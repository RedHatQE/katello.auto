(defproject katello.auto "1.0.0-SNAPSHOT"
  :description "Katello automation"  
  :main ^{:skip-aot true} katello.tests.suite
  :dependencies [[org.clojure/clojure "1.5.0"]
                 [org.clojure/tools.macro "0.1.2"]
                 [org.clojure/tools.cli "0.2.1"]
                 [org.clojure/data.json "0.1.1"]
                 [org.clojure/data.zip "0.1.1"]
                 
                 [org.seleniumhq.selenium/selenium-server "2.33.0"]
                 [org.seleniumhq.selenium/selenium-java "2.33.0"]

                 [slingshot "0.10.3"]
                 [inflections "0.6.2"]
                 
                 [test.tree.jenkins "0.10.0-SNAPSHOT" :exclusions [org.clojure/clojure]]
                 [test.tree "0.10.0-SNAPSHOT" :exclusions [org.clojure/clojure]]
                 [test.tree.runner "0.9.0-SNAPSHOT" :exclusions [org.clojure/clojure]]
                 
                 
                 [com.redhat.qe/test.assert "1.0.0-SNAPSHOT"]
                 [com.redhat.qe/extended-selenium "1.1.1-SNAPSHOT" :exclusions [org.seleniumhq.selenium.client-drivers/selenium-java-client-driver]]
                 [com.redhat.qe/ssh-tools "1.0.0"]
                 [com.redhat.qe/xmlrpc-client-tools "1.0.3"]
                 [com.redhat.qe/deltacloud.client "1.2.0-SNAPSHOT"]
                 
                 [clj-tagsoup "0.3.0" :exclusions [org.clojure/clojure org.clojure/data.xml]]
                 [clj-http "0.6.4"]

                 [ui.navigate "0.4.0-SNAPSHOT"]
                 [bugzilla.checker "0.2.0-SNAPSHOT"]
                 [github.checker "0.2.0-SNAPSHOT"]
                 [fn.trace "1.4.0-SNAPSHOT"]]
  :jvm-opts ["-Xmx640m" "-Xms48m"]
  :repl-options {:init-ns katello.repl}
  :repositories ^:replace [["clojars" {:url "http://clojars.org/repo"
                                       :snapshots {:update :always}}]
                           ["central" {:url "http://repo1.maven.org/maven2"
                                       :snapshots false}]])
