(ns katello.trace
  (:require [clojure.contrib.trace :as trace]
            [robert.hooke :as hook])
  (:use [clojure.contrib.core :only [-?>]]))

(defn trace-fn-call
  "Traces a single call to a function f with args.  'name' is the
  symbol name of the function."
  [f & args]  
  (let [id (gensym "TR")
        m (meta f)]
    (trace/tracer id (str (trace/trace-indent)
                          (pr-str (cons
                                   (symbol (str (:ns m)) (str (m :name)))
                                   args))))
    (let [[value err] (binding [trace/*trace-depth* (inc trace/*trace-depth*)]
                        (try [(apply f args) nil]
                             (catch Exception e [e e])))]
      (trace/tracer id (str (trace/trace-indent) "=> " (pr-str value)))
      (when err (throw err))
      value)))

(defn trace [v]
  (if-not (some #{trace-fn-call} (-?> (deref v) meta :robert.hooke/hook deref))
    (hook/add-hook v trace-fn-call)))

(defn untrace [v]
  (hook/remove-hook v trace-fn-call))

(defn with-all-in-ns [f & namespaces]
  (doseq [namespace namespaces]
    (require namespace)
    (doseq [[_ v] (ns-interns namespace)]
      (if (fn? (deref v))
        (f v)))))


