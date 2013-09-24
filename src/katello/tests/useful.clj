(ns katello.tests.useful
  (:require [clojure.zip :as zip]
            [katello :as kt]
            [webdriver :as wd]
            [clj-webdriver.taxi :as browser]
            (katello [rest :as rest]
                     [ui :as ui]
                     [tasks :refer [with-unique uniqueify uniques]]
                     [sync-management :as sync]
                     [manifest :as manifest]
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

(defn third-lvl-menu-click
  "Clicks on a 3rd level menu item by executing js. Workaround for
  webdriver issue that considers some menu items hidden when they are visible."
  [loc-id]
  (Thread/sleep 1000)
  (wd/move-to (ui/third-level-link loc-id))
  (browser/execute-script (ui/js-id-click loc-id))
  (wd/ajax-wait))

(defn fresh-repos
  "Infite seq of unique repos (in new provider/product) in given org,
   with given url."
  ([org url] (fresh-repos org url "yum"))
  ([org url repo-type]
    (for [[prov prod repo] (apply map list
                                (map tasks/uniques
                                     (list (kt/newProvider {:name "sync", :org org})
                                           (kt/newProduct {:name "sync-test1"})
                                           (kt/newRepository {:name "testrepo", :url url, :repo-type repo-type}))))]
    (assoc repo :product (assoc prod :provider prov)))))

(defn fresh-repo "New repo in a new product in a new provider"
  ([org url] (fresh-repo org url "yum"))
  ([org url repo-type]
    (first (fresh-repos org url repo-type))))

(defn prepare-org-fetch-org []
  (let [org (uniqueify (kt/newOrganization {:name "redhat-org"}))
        envz (take 3 (uniques (kt/newEnvironment {:name "env", :org org})))]
    (ui/create org)
    (doseq [e (kt/chain envz)]
      (ui/create e))
    org))

(defn new-manifest [redhat-manifest?]
  (let [org       (prepare-org-fetch-org)
        provider  (assoc kt/red-hat-provider :org org)
        fetch-manifest  (uniqueify (manifest/download-original-manifest redhat-manifest?))
        manifest  (assoc fetch-manifest :provider provider)]
    manifest))

(defn add-product-to-cv
  [org target-env repo]
  (with-unique [cv (kt/newContentViewDefinition {:name "con-def"
                                                 :published-name "publish-name"
                                                 :org org})]
    (ui/create-all-recursive (list org target-env))
    (create-recursive repo)
    (sync/perform-sync (list repo))
    (ui/create cv)
    (ui/update cv assoc :products (list (kt/product repo)))
    cv))
