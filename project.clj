(defproject katello.auto "1.0.0-SNAPSHOT"
  :description "Katello automation"  
  :main ^{:skip-aot true} katello.tests.suite
  :omit-default-repositories true
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [swank-clojure "1.4.2"]
                 [test.tree.jenkins "0.2.0-SNAPSHOT"]
                 [test.tree "0.8.0-SNAPSHOT"]
                 [test.tree.runner "0.7.5-SNAPSHOT"]
                 [org.clojure/data.json "0.1.1"]
                 [org.clojure/data.zip "0.1.0"]
                 [ui.navigate "0.2.2-SNAPSHOT"]
                 [com.redhat.qe/test.assert "1.0.0-SNAPSHOT"]
                 [com.redhat.qe/extended-selenium "1.1.1-SNAPSHOT" :exclusions [org.seleniumhq.selenium.client-drivers/selenium-java-client-driver]]
                 [org.seleniumhq.selenium/selenium-server "2.26.0"]
                 [org.seleniumhq.selenium/selenium-java "2.26.0"]
                 [com.redhat.qe/ssh-tools "1.0.0"]
                 [com.redhat.qe/xmlrpc-client-tools "1.0.3"]
                 [slingshot "0.10.3"]
                 [inflections "0.6.2"]
                 [clj-http "0.5.5"]
                 [org.clojure/tools.cli "0.2.1"]
                 [bugzilla.checker "0.1.2-SNAPSHOT"]
                 [fn.trace "1.3.3-SNAPSHOT"]
                 [com.redhat.qe/deltacloud.client "1.1.0-SNAPSHOT"]]
  :jvm-opts ["-Xmx384m" "-Xms48m"]
  :repositories {"my-clojars" {:url "http://clojars.org/repo"
                               :snapshots {:update :always}}
                 "my-central" {:url "http://repo1.maven.org/maven2"
                               :snapshots false}}
  :plugins [[codox "0.6.3"]])
