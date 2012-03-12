(defproject katello "1.0.0-SNAPSHOT"
  :description "Katello automation"  
  :main katello.tests.suite
  :omit-default-repositories true
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [test.tree "0.6.1-SNAPSHOT"]
                 [org.clojure/data.json "0.1.1"]
                 [webui-framework "1.0.2-SNAPSHOT"]
                 [slingshot "0.8.0"]
                 [inflections "0.6.2"]
                 [clj-http "0.3.2"]
                 [bugzilla.checker "0.1.1"]
                 [fn.trace "1.3.2.0-SNAPSHOT"]]
  :dev-dependencies [[slamhound "1.2.0"]]
  
  :jvm-opts ["-Xmx192m"]
  :repositories {"my-clojars" {:url "http://clojars.org/repo"
                            :snapshots {:update :always}}
                 "my-central" {:url "http://repo1.maven.org/maven2"
                            :snapshots false}})

(comment "Execute this in the repl to load everything and start selenium"
         (do
           (do (require 'katello.tasks :reload-all)
               (require 'katello.conf :reload)
               (require 'katello.tests.setup :reload)
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
           (katello.tests.setup/new-selenium "*firefox" true)
           (katello.tests.setup/start-selenium)) ;;<-here for selenium
         )

(comment "Execute this in the repl to create some test entities via API"

         (do (com.redhat.qe.tools.SSLCertificateTruster/trustAllCerts)
             (com.redhat.qe.tools.SSLCertificateTruster/trustAllCertsForApacheXMLRPC)
             (require '[katello.tests.promotions]
                      '[katello.tests.sync_management]
                      '[katello.tests.templates]
                      '[katello.conf])
             (katello.conf/init)
             (katello.tests.promotions/setup)
             (katello.tests.sync_management/setup)
             (katello.tests.templates/setup-content))
         )
