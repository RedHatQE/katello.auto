(defproject katello.auto "1.0.0-SNAPSHOT"
  :description "Katello automation"  
  :main katello.tests.suite
  :omit-default-repositories true
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [test.tree.jenkins "0.1.0-SNAPSHOT"]
                 [test.tree "0.7.0-SNAPSHOT"]
                 [org.clojure/data.json "0.1.1"]
                 [ui.navigate "0.1.0"]
                 [webui-framework "1.0.2-SNAPSHOT"]
                 [slingshot "0.8.0"]
                 [inflections "0.6.2"]
                 [clj-http "0.3.2"]
                 [bugzilla.checker "0.1.2-SNAPSHOT"]
                 [fn.trace "1.3.2.0-SNAPSHOT"]]
  :dev-dependencies [[lein-autodoc "0.9.0"]]
  :checksum-deps false
  :jvm-opts ["-Xmx192m"]
  :repositories {"my-clojars" {:url "http://clojars.org/repo"
                               :snapshots {:update :always}}
                 "my-central" {:url "http://repo1.maven.org/maven2"
                               :snapshots false}}
  :autodoc {:name "Katello GUI Automation"
            :web-src-dir "https://github.com/weissjeffm/katello.auto/blob/"})

(comment 
         "Execute this in the repl to load everything and start selenium"
           (do  ;;here for eclipse/selenium
             
             (do (require 'katello.ui-tasks :reload-all)
                 (require 'katello.conf :reload)
                 (require 'katello.setup :reload)
                 (require 'katello.client :reload)

                 (com.redhat.qe.tools.SSLCertificateTruster/trustAllCerts)
                 (com.redhat.qe.tools.SSLCertificateTruster/trustAllCertsForApacheXMLRPC)
                 
                 (katello.conf/init)
                 (when katello.conf/*clients*
                   (katello.client/connect (katello.client/new-runner
                                            (first katello.conf/*clients*)
                                            "root" nil
                                            (@katello.conf/config :client-ssh-key)
                                            (@katello.conf/config :client-ssh-key-passphrase))))) ;;<-here for api only
             (katello.setup/new-selenium "*firefox" true)
             (katello.setup/start-selenium)) ;;<-here for emacs/selenium
           )

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
