(ns kalpana.trace
  (:require [clojure.contrib.trace :as trace]
            [robert.hooke :as hook]))

(defn trace-fn-call
  "Traces a single call to a function f with args.  'name' is the
  symbol name of the function."
  [f & args]  
  (let [id (gensym "call")
        m (meta f)]
    (trace/tracer id (str (trace/trace-indent)
                          (pr-str (cons
                                   (symbol (str (:ns m)) (str (m :name)))
                                   args))))
    (let [value (binding [trace/*trace-depth* (inc trace/*trace-depth*)]
                  (apply f args))]
      (trace/tracer id (str (trace/trace-indent) "=> " (pr-str value)))
      value)))

(defn trace [v]
  (hook/add-hook v trace-fn-call))

(defn untrace [v]
  (hook/remove-hook v trace-fn-call))

(defn trace-all [namespace]
  (doall (for [[k v] (ns-interns namespace)] (trace v))))
