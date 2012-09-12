 (ns leiningen.autodoc-publish
  (:use 
    [clojure.java.shell :only [sh with-sh-dir]]))

 (defn sh-timeout
   "Runs sh command and terminates it after [timeout] seconds"
   [timeout-in-seconds & args]
    (.get
      (future-call #(apply sh args))
      timeout-in-seconds
      (java.util.concurrent.TimeUnit/SECONDS)))
 
 (defn tokenize
   "Splits string to tokens, to be used with (apply sh (tokenize \"command string\"))"
   [command] (apply re-seq #"\S+" command))
 
 (defn run-sh-command
   "clojure.java.shell wrapper"
   ([command]
    (println command)
    (let [r (apply sh (tokenize command))]
      (if (= (:exit r) 0)
        (println (:out r))
        (println (:err r)))
       r))

   ([timeout command]
   ((println command) 
     (try
       (apply sh-timeout 120 (tokenize command))
       (catch Exception e (prn "finished on" timeout "second timeout"))))))
 

(defn get-current-git-repo []
  (.trim (:out (sh "git" "config" "--get" "remote.origin.url"))))

(defn autodoc-publish 
  "Generates and updates autodoc in github gh-pages,
  tomfaulhaber.github.com/autodoc/"
  [project & args]
  
 (run-sh-command "rm -Rf autodoc")
 (run-sh-command (str "git clone" (get-current-git-repo) "autodoc"))
 (with-sh-dir "autodoc" 
    (run-sh-command "git checkout -b gh-pages origin/gh-pages"))
 (run-sh-command 120 "lein autodoc")
 (with-sh-dir "autodoc"
   (run-sh-command  "git commit -as -m update")
   (run-sh-command  "git push origin gh-pages")))
  
