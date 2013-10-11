(defproject com.redhat.qe/katello.auto "1.0.0-SNAPSHOT"
  :description "Katello automation"  
  :main ^{:skip-aot true} katello.tests.suite
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/tools.cli "0.2.1"]  ; reads cli options
                 [org.clojure/data.json "0.2.2"]  ; parses json responses from katello API
                 [org.clojure/data.zip "0.1.1"]  ; zipper structures for various uses
                 
                 [org.seleniumhq.selenium/selenium-server "2.34.0"]
                 [org.seleniumhq.selenium/selenium-java "2.34.0"]
               
                 [slingshot "0.10.3"]  ; advanced error handling
                 [inflections "0.6.2"] ; for plural/singular of words TODO: (probably not needed)
                 
                 [test.tree.jenkins "0.10.1-SNAPSHOT" :exclusions [org.clojure/clojure]] ; webdriver, sauce, debugging
                 [test.tree "1.0.0-SNAPSHOT" :exclusions [org.clojure/clojure]] ; test harness
                                  
                 [com.redhat.qe/test.assert "1.0.0-SNAPSHOT"] ; assertions
                 [com.redhat.qe/xmlrpc-client-tools "1.0.3"] ; Disables ssl cert checking on java http client
                 [com.redhat.qe/ovirt.client "0.1.0-SNAPSHOT"] ; provisions client machines

                 [bouncycastle/bcprov-jdk15 "140"] ; crypto lib (not sure what requires this) TODO: try removing
                 
                 [clj-tagsoup "0.3.0" :exclusions [org.clojure/clojure org.clojure/data.xml]] ; html parser
                 [clj-http "0.6.4"] ; http client
                 [clj-ssh "0.5.6"] ; ssh client for connecting to client machines

                 [clj-webdriver "0.6.0" :exclusions [org.seleniumhq.selenium/selenium-java
                                                     org.seleniumhq.selenium/selenium-server]]
                 
                 [ui.navigate "0.4.0-SNAPSHOT"] ; tool for representing UI as tree of pages
                 [bugzilla.checker "0.2.0-SNAPSHOT"] 
                 [github.checker "0.2.0-SNAPSHOT"]
                 [fn.trace "1.4.0-SNAPSHOT"]] ; trace logging
  
  :jvm-opts ["-Xmx640m" "-Xms48m"  ; max, start heap sizes
             "-XX:-OmitStackTraceInFastThrow"]  ; Prevents exceptions with empty stack traces
  :repl-options {:init-ns katello.repl}
  :repositories ^:replace [["clojars" {:url "http://clojars.org/repo"
                                       :snapshots {:update :always}}]
                           ["central" {:url "http://repo1.maven.org/maven2"
                                       :snapshots false}]])
