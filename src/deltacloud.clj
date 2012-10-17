(ns deltacloud
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :refer [split]]
            [clojure.core.incubator :refer [-?> -?>>]]))

(defmacro loop-with-timeout
  "Similar to clojure.core/loop, but adds a timeout to break out of
  the loop if it takes too long. timeout is in ms. bindings are the
  bindings that would be provided to clojure.core/loop. body is the
  loop body to execute if the timeout has not been reached. timeout-body
  is the body to execute if the timeout has been reached. timeout-body
  defaults to throwing a RuntimeException."
  [timeout bindings body & [timeout-body]]
  `(let [starttime# (System/currentTimeMillis)]
     (loop ~bindings
       (if  (> (- (System/currentTimeMillis) starttime#) ~timeout)
         ~(or timeout-body `(throw (RuntimeException. (str "Hit timeout of " ~timeout "ms."))))
         ~body))))

(defn connection
  "Returns a mapping of http methods to functions. In those functions,
  you can specify :href in the req, and that will be used as the url
  and uri will be ignored."
  [baseurl username password]
  {:pre [(and baseurl username password)]}
  (let [connectize (fn [method]
                     (fn [uri & [req]]
                       (-?> (method (or (:href req) (format "%s/%s" baseurl uri))
                                    (dissoc (merge req {:basic-auth [username password]
                                                        :accept :json
                                                        :content-type :json})
                                            :href))
                            :body
                            json/read-json)))
        method-names [`http/get `http/post `http/delete `http/put]
        keywordify #(-> % str (split #"/") last keyword)]
    (zipmap (map keywordify method-names)
            (map (comp connectize resolve) method-names))))

(defn instances "get all instances"
  [conn]
  (-> ((conn :get) "instances")
     :instances
     :instance))

(defmacro defstates [m]
  `(do ~@(for [[k v] m]
          `(defn ~k [i#]
             (-> i# :state (= ~v))))))

(defstates {stopped? "STOPPED"
            running? "RUNNING"
            pending? "PENDING"})

(defn by-name [inst-name]
  #(= inst-name (:name %)))

(defn instance-by-name [conn inst-name]
  (->> conn instances (filter (by-name inst-name)) first))

(defn name-indexed
  "Takes a map of instance creation parameters (such at image_id,
etc), returns an infinite sequence of maps, each one add
'-n' appended to the name, where n is the index."
  [m]
  (for [i (iterate inc 1)]
    (update-in m [:name] #(format "%s-%d" % i))))

(defn small-instance-properties
  "A set of properties for a small instance, requires a name and image_id."
  [{:keys [name image-id]}]
  {:pre [(and name image-id)]}
  {:name name
   :image_id image-id
   :hwp_cpus "2"
   :hwp_memory "256"})

(defn get-actions [conn i]
  (let [call-method (fn [link]
                      (let [method (-> link :method keyword conn)]
                        (method nil {:href (:href link)})))
        method-entry (fn [link]
                       [(-> link :rel keyword) (partial call-method link)])]
    
    (->> i :actions :link (map method-entry) (into {}))))

(defn action-available-pred [action]
  #(->> % :actions :link (map :rel) (some #{action})))

(defn ip-address [inst]
  (-?>> inst :public_addresses :address
        (some #(re-find #"\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}" %))))

(defn refresh "Reloads the instance from deltacloud"
  [conn i]
  (:instance ((conn :get) (format "instances/%s" (:id i)))))

(defn wait-for
  "Wait for pred to become true on instance i (refreshing
  periodically)"
  [conn i pred]
  (loop-with-timeout 600000 [i i]
    (if (pred i)
      i
      (do
        #_(prn (select-keys i [:name :state :public_addresses :actions]))
        (Thread/sleep 10000)
        (recur (refresh conn i))))))

(defn perform-action-wait
  "Performs action on instance i, waits for pred to become true."
  [conn i action pred]
  (let [avail-actions (get-actions conn i)]
    (assert (some #{action} (keys avail-actions))
            (format "%s not one of available actions on instance: %s"
                    action
                    (keys avail-actions)))
    (wait-for conn
             (-> action avail-actions .invoke :instance)
             pred)))

(defn create-instance
  "Creates an instance with the given base baseurl, credentials and
  instance properties. Returns the instance data."
  [conn m]
  
  (wait-for conn
            (-> ((conn :post) "instances" {:query-params m}) :instance)
            (action-available-pred "start")))

(defn start [conn i]
  (perform-action-wait conn i :start ip-address))

(defn stop [conn i]
  (perform-action-wait conn i :stop stopped?))

(defn destroy [conn i]
  (perform-action-wait conn i :destroy nil?))

(defn provision
  "Create an instance, start it and wait for it to come up."
  [conn m]
  (assoc (start conn (create-instance conn m))
    :deltacloud-connection conn))

(defn unprovision "Whatever state the instance is in, destroy it"
  ([i] (unprovision (:deltacloud-connection i) i))
  ([conn i]
     {:pre [conn]}
     (cond (stopped? i) (destroy conn i)
           (running? i) (destroy conn (stop conn i))
           :else (unprovision (wait-for conn i
                                        (some-fn stopped? running?))))))

(defn provision-all
  "Provision instances with given properties (a list of maps), in parallel.
   See name-indexed to easily generate such a list. Returns the
   instances data from deltacloud, and the deltacloud connection."
  [conn instance-props]
  {:instances (->> (for [inst-prop instance-props]
                   (future (provision conn inst-prop)))
                 doall
                 (map deref))
   :deltacloud-connection conn})



(defn unprovision-all
  "Destroy all the given instances, in parallel."
  ([instances] (unprovision-all (:deltacloud-connection instances) (:instances instances)))
  ([conn instances]
     (map deref
          (doall (for [i instances]
                   (future (unprovision conn i)))))))

(defmacro with-instances "Executes body with bound instances."
  [instances-binding & body]
  `(let ~instances-binding
     (try
       ~@body
       (finally (unprovision-all ~(first instances-binding))))))

(defmacro with-instance [instance-binding & body]
  `(let ~instance-binding
     (try
       ~@body
       (finally (unprovision ~(first instance-binding))))))