(ns katello
  (:refer-clojure :exclude [defrecord]))

(defmacro defrecord [rec args]
  `(do (clojure.core/defrecord ~rec ~args)
       (def ~(symbol (str "new" rec)) ~(symbol (str "map->" rec)))))


(defrecord Organization [id name label description initial-env-name
                         initial-env-label initial-env-description])


(defrecord Environment [id name label description org prior])

(def library (newEnvironment {:name "Library"}))


(defrecord Provider [id name description org])

(defrecord Product [id name provider])
