(ns katello.blockers
  (:require test.tree
            [github.checker :as gh]
            [bugzilla.checker :as bz]))

(extend-protocol test.tree/Blocker
  github.checker.Issue
  (blockers [i _] (let [i (gh/get-issue i)]
                    (if (gh/closed? i) '()
                        (list i))))

  bugzilla.checker.Bug
  (blockers [b _] (let [b (bz/get-bug b)]
                    (if (bz/fixed? b) '()
                        (list b)))))

;; Github
(def auto-issue #(github.checker.Issue. "RedHatQE" "katello.auto" %))

(defn auto-issues [& numbers]
  (map auto-issue numbers))

(def katello-issue #(github.checker.Issue. "Katello" "katello" %))

(defn katello-issues [& numbers]
  (map katello-issue numbers))

;;Bugzilla
(def bz-bug bz/bug)

(defn bz-bugs [& ids]
  (map bz-bug ids))
