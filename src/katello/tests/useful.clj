(ns katello.tests.useful
  (:require [clojure.zip :as zip]
            [katello :as kt]
            (katello [rest :as rest]
                     [tasks :as tasks])))

(defn ensure-exists [ent]
  (when-not (rest/exists? ent)
    (rest/create ent)))

(defn ent-zip "A zipper to traverse nested katello entites"
  [ent]
  (zip/zipper (constantly true)
              #(seq (filter (partial satisfies? rest/CRUD) (vals %)))
              #(throw (RuntimeException. "Editing entity zipper is not supported."))
              ent))

(defn create-recursive
  "Recursively create in katello, all the entites that satisfy
   katello.rest/CRUD (innermost first).  Example, an env that contains
   a field for its parent org, the org would be created first, then
   the env."
  [ent & [{:keys [check-exist?]
           :or {check-exist? true}
           :as opts}]]
  (let [match? (fn [e1 e2] (and (= (class e1) (class e2)
                                   (:name e1) (:name e2))))
        already-created? (fn [e s] (seq (filter (partial match? e) s)))]
    ;; only create what hasn't been created already
    (loop [ents (->> ent
                     ent-zip
                     (iterate zip/next)
                     (take-while (complement zip/end?))
                     (map zip/node)
                     reverse)
           already-created (list)]
      (let [ent (first ents)]
        (when (and ent (not (already-created? ent already-created)))
          (if check-exist?
            (ensure-exists ent)
            (rest/create ent))
          (recur (rest ents) (conj already-created ent)))))))

(defn create-all-recursive [ents & [{:keys [check-exist?] :as m}]]
  (doseq [ent ents]
    (create-recursive ent m)))

(defn create-series
  "Create (within katello) a lazy seq of entities based
  on ent"
  [ent]
  (lazy-seq (map rest/create (tasks/uniques ent))))

(defn fresh-repos
  "Infite seq of unique repos (in new provider/product) in given org,
   with given url."
  [org url]
  (for [[prov prod repo] (apply map list
                                (map tasks/uniques
                                     (list (kt/newProvider {:name "sync", :org org})
                                           (kt/newProduct {:name "sync-test1"})
                                           (kt/newRepository {:name "testrepo", :url url}))))]
    (assoc repo :product (assoc prod :provider prov))))

(defn fresh-repo "New repo in a new product in a new provider"
  [org url]
  (first (fresh-repos org url)))

