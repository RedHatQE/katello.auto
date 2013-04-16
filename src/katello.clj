(ns katello
  (:refer-clojure :exclude [defrecord])
  )

(defmacro defrecord [rec args]
  `(do (clojure.core/defrecord ~rec ~args
         clojure.lang.IFn
         (invoke [this#] this#)
         (invoke [this# query#] (get this# query#))
         (applyTo [this# args#] (get-in this# args#)))
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

(defrecord Template [id name product org content])

(ns-unmap *ns* 'System) ; collision w java.lang.System
(defrecord System [id name env service-level])

(defrecord GPGKey [id name org content])

(defrecord User [id name email password password-confirm default-org default-env])

(defrecord Role [id name users permissions])

(defrecord Permission [name role org resource-type verbs])

(defrecord ActivationKey [id name env description])

(defrecord SystemGroup [id name systems org])

(defrecord ContentView [id name description composite composite-name org])

(defrecord Manifest [provider file-path url])

(def red-hat-provider (newProvider {:name "Red Hat"}))

(defrecord SyncPlan [id name org interval])

(defrecord Pool [id productId org])

(defrecord Subscription [id system pool quantity])

;; Relationship protocol

(defprotocol BelongsTo
  (org [x])
  (env [x])
  (product [x])
  (provider [x])
  (repository [x])
  (parent [x]))

(def relationships
  {Organization {:org identity, :parent (constantly nil)}
   Environment {:org :org, :env identity, :parent #'org}  ; the org is in the env's :org field
   Provider {:org :org, :provider identity, :parent #'org}
   Product {:org (comp #'org #'provider), :provider :provider, :product identity, :parent #'provider} ; the org is the provider's org
   Repository {:org (comp #'org #'product), :product :product, :repository identity, :parent #'product} ; the org is the product's org
   Package {:org (comp #'org #'product), :product :product, :parent #'product}
   Erratum {:org (comp #'org #'product), :product :product, :parent #'product}
   Template {:org (fn [t] (or (:org t)
                              (-> t product org)))
             :product :product}
   System {:org (comp #'org #'env), :env :env, :parent #'org}
   GPGKey {:org :org, :parent #'org}
   Permission {:org :org, :parent #'org}
   ActivationKey {:org (comp #'org #'env), :env :env, :parent #'env}
   SystemGroup {:org :org}
   ContentView {:org :org, :parent #'org}
   Manifest {:org (comp #'org #'provider), :provider :provider, :parent #'provider}
   SyncPlan {:org :org, :parent #'org}})

(doseq [[rec impls] relationships]
  (extend rec BelongsTo impls))



(defn chain
  "Sets the next and prior fields of successive envs to make a doubly
  linked list."
  [environments] {:pre [(apply = (map org environments))]} ; all in same org
  (let [org (-> environments first org)
        f (fn [envs latest-env]
            (let [l (last envs)
                  r (butlast envs)]
              (conj (vec r) (assoc l :next latest-env) (assoc latest-env :prior l))))]
    (rest (reduce f (vector (assoc library :org org)) (vec environments)))))
