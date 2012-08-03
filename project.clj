(defproject katello.auto "1.0.0-SNAPSHOT"
  :description "Katello automation"  
  :main ^{:skip-aot true} katello.tests.suite
  :omit-default-repositories true
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [swank-clojure "1.4.2"]
                 [test.tree.jenkins "0.1.0-SNAPSHOT"]
                 [test.tree "0.7.5-SNAPSHOT"]
                 [org.clojure/data.json "0.1.1"]
                 [ui.navigate "0.1.0"]
                 [com.redhat.qe/tools.verify "1.0.0"]
                 [com.redhat.qe/extended-selenium "1.1.0-SNAPSHOT" :exclusions [org.seleniumhq.selenium.client-drivers/selenium-java-client-driver]]
                 [org.seleniumhq.selenium/selenium-server "2.25.0"]
                 [org.seleniumhq.selenium/selenium-java "2.25.0"]
                 [com.redhat.qe/ssh-tools "1.0.0"]
                 [com.redhat.qe/xmlrpc-client-tools "1.0.3"]
                 [slingshot "0.10.3"]
                 [inflections "0.6.2"]
                 [http.async.client "0.4.5"]
                 [org.clojure/tools.cli "0.2.1"]
                 [bugzilla.checker "0.1.2-SNAPSHOT"]
                 [fn.trace "1.3.2.0-SNAPSHOT"]]
  :dev-dependencies [[lein-autodoc "0.9.0"]]
  :checksum-deps false
  :jvm-opts ["-Xmx192m" "-Xms96m"]
  ;; :repl-init bootstrap
  :repositories {"my-clojars" {:url "http://clojars.org/repo"
                               :snapshots {:update :always}}
                 "my-central" {:url "http://repo1.maven.org/maven2"
                               :snapshots false}}
  :autodoc {:name "Katello GUI Automation"
            :web-src-dir "https://github.com/weissjeffm/katello.auto/blob/"})

;; if you're looking for that comment block to start selenium, that's
;; been replaced by something easier. You no longer need to start
;; selenium server yourself, it's now embedded (so if you started one
;; on port 4444, kill it). Just type (load "bootstrap") at the repl,
;; that will start both selenium server and the client.

(comment "Execute this in the repl to create some test entities via API"

         (do (com.redhat.qe.tools.SSLCertificateTruster/trustAllCerts)
             (com.redhat.qe.tools.SSLCertificateTruster/trustAllCertsForApacheXMLRPC)
             (require '[katello.tests.promotions]
                      '[katello.tests.sync_management]
                      '[katello.tests.templates]
                      '[katello.conf])
             (katello.conf/init)
             (katello.tests.promotions/setup)  ;;needs updating, fn names have changed
             (katello.tests.sync_management/setup)
             (katello.tests.templates/setup-content))
         )
