(ns katello
  (:refer-clojure :exclude [defrecord]))

(defn instance-or-nil? [c i]
  (or (not c)
      (nil? i)
      (instance? c i)))

(defmacro defrecord [rec args]
  (let [annotated-arg-syms (filter #(not (nil? (meta %))) args)
        annotations (zipmap annotated-arg-syms 
                            (map (comp :tag meta) annotated-arg-syms))
        constr-arg-sym (gensym "m")]
    `(do (clojure.core/defrecord ~rec ~args
           clojure.lang.IFn
           (invoke [this#] this#)
           (invoke [this# query#] (get this# query#))
           (applyTo [this# args#] (get-in this# args#)))
     
         (defn ~(symbol (str "new" rec)) [{:keys ~(vec annotated-arg-syms) :as ~constr-arg-sym}] 
           {:pre ~(vec (for [[sym clazz] annotations]
                            `(instance-or-nil? ~clazz ~sym) ))}
           (~(symbol (str "map->" rec)) ~constr-arg-sym)))))

;; Define records for all entities we'll be testing with

(defrecord Organization [id name label description initial-env])

(defrecord Environment [id name label description ^Organization org prior])

(def library (newEnvironment {:name "Library"})) ;  Library is a special
                                        ;  environment so create a var
                                        ;  to refer to it later
(defn mklibrary
  "Creates a library record for a particular org and next
   environment (used for env selection in UI)"
  [env]
  (assoc library :org (:org env) :next env))

(defrecord Provider [id name description ^Organization org])

(defrecord Product [id name ^Provider provider])

(defrecord Repository [id name ^Product product url gpg-key])

(defrecord Changeset [id name ^Environment env deletion?])

(ns-unmap *ns* 'Package) ; collision w java.lang.Package
(defrecord Package [id name ^Product product])

(defrecord Erratum [id name ^Product product])

(defrecord Template [id name ^Product product ^Organization org content])

(ns-unmap *ns* 'System) ; collision w java.lang.System
(defrecord System [id name ^Environment env service-level])

(defrecord GPGKey [id name ^Organization org content])

(defrecord User [id name email password password-confirm ^Organization default-org ^Environment default-env])

(defrecord Role [id name users permissions])

(defrecord Permission [name ^Role role ^Organization org resource-type verbs])

(defrecord ActivationKey [id name ^Environment env description])

(defrecord SystemGroup [id name systems  ^Organization org])

(defrecord Manifest [provider file-path url])

(def red-hat-provider (newProvider {:name "Red Hat"}))

(defrecord SyncPlan [id name ^Organization org interval])

(defrecord Pool [id productId ^Organization org])

(defrecord Subscription [id ^System system pool quantity])

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
   Repository {:org (comp #'org #'product), :product :product, :provider (comp #'provider #'product)
               :repository identity, :parent #'product} ; the org is the product's org
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
