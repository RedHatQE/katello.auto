(ns kalpana.trace
  (:require [clojure.contrib.trace :as trace]
            [robert.hooke :as hook])
  (:use [clojure.contrib.core :only [-?>]]))

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
  (if-not (some #{trace-fn-call} (-?> (deref v) meta :robert.hooke/hook deref))
    (hook/add-hook v trace-fn-call)))

(defn untrace [v]
  (hook/remove-hook v trace-fn-call))

(defn with-all-in-ns [f & namespaces]
  (doall (for [namespace namespaces]
           (do (require namespace)
               (for [[_ v] (ns-interns namespace)]
                 (if (fn? (deref v))
                   (f v)))))))


