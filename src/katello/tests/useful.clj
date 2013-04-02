(ns katello.tests.useful
  (:require (katello [rest :as rest]
                     [tasks :as tasks])))

(defn create-recursive
  "Recursively create in katello, all the entites that satisfy
   katello.rest/CRUD (innermost first).  Example, an env that contains
   a field for its parent org, the org would be created first, then
   the env." [ent & [{:keys [check-exist?] :or {:check-exist true}}]]
   (doseq [field (vals ent) :when (satisfies? rest/CRUD field)]
     (create-recursive field))
   (when (or (and check-exist? (rest/not-exists? ent))
             (not check-exist?))
     (rest/create ent)))

(defn create-all-recursive [ents & [{:keys [check-exist?] :as m}]]
  (doseq [ent ents]
    (create-recursive ent m)))

(defn create-series
  "Create (within katello) a lazy seq of entities based
  on ent"
  [ent]
  (lazy-seq (map rest/create (tasks/uniques ent))))
