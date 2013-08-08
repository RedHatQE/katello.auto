(ns katello.blockers
  (:require test.tree
            [katello.rest :as rest]
            [github.checker :as gh]
            [bugzilla.checker :as bz]))

(defn matches-current-deployment
  "Checks whether metadata on the bug b matches the current
  deployment.  Will only return false if meta contains :headpin true,
  and this is a katello deployment, or vice versa."
  [b]
  (let [cur-deployment (if (rest/is-katello?)
                         :katello
                         :headpin)
        affects-meta (select-keys (meta b) [:katello :headpin])]
    (if (-> affects-meta count (= 0)) true
        (affects-meta cur-deployment))))

(extend-protocol test.tree/Blocker
  github.checker.Issue
  (blockers [i _] (let [i (gh/get-issue i)]
                    (if (gh/closed? i) '()
                        (list (gh/link i)))))

  bugzilla.checker.Bug
  (blockers [b _] (and (matches-current-deployment b)
                       (let [b (bz/get-bug b)]
                         (if (bz/fixed? b)
                           '()
                           (list (bz/link b)))))))

;; Github
(def auto-issue #(github.checker.Issue. "RedHatQE" "katello.auto" %))

(defn auto-issues [& numbers]
  (map auto-issue numbers))

(def katello-issue #(github.checker.Issue. "Katello" "katello" %))

(defn katello-issues [& numbers]
  (map katello-issue numbers))

;;Bugzilla
(defn bz-bug [id & [meta-kw]]
  "Creates a Bug record with optional metadata.  to get metadata
   {:headpin true} (meaning, headpin-only bug), you'd call
     (bz-bug '123456' :headpin)"
  (with-meta (bz/bug id) {meta-kw true}))

(defn bz-bugs [& ids]
  (map bz-bug ids))
