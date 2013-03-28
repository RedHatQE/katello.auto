(ns katello
  (:refer-clojure :exclude [defrecord]))

(defmacro defrecord [rec args]
  `(do (clojure.core/defrecord ~rec ~args)
       (def ~(symbol (str "new" rec)) ~(symbol (str "map->" rec)))))

;; Define records for all entities we'll be testing with

(defrecord Organization [id name label description initial-env-name
                         initial-env-label initial-env-description])


(defrecord Environment [id name label description org prior])

(def library (newEnvironment {:name "Library"})) ;  Library is a special
                                        ;  environment so create a var
                                        ;  to refer to it later
(defn mklibrary
  "Creates a library record for a particular org and next
   environment (used for env selection in UI)"
  [env]
  (assoc library :org (:org env) :next env))


(defrecord Provider [id name description org])

(defrecord Product [id name provider])

(defrecord Repository [id name product url gpg-key])

(defrecord Changeset [id name env deletion?])

(ns-unmap *ns* 'Package) ; collision w java.lang.Package
(defrecord Package [id name product])

(defrecord Erratum [id name product])

(defrecord Template [id name product])

(ns-unmap *ns* 'System) ; collision w java.lang.System
(defrecord System [id name env service-level])

(defrecord GPGKey [id name org content])

(defrecord User [id name email password password-confirm default-org default-env])

(defrecord Role [id name users permissions])

(defrecord Permission [name role org resource-type verbs])

(defrecord ActivationKey [id name env description])

(defrecord SystemGroup [id name systems org])

(defrecord Manifest [provider file-path url])

(def red-hat-provider (newProvider {:name "Red Hat"}))

(defrecord SyncPlan [id name org interval])
