(ns katello.trace
  (:require [clojure.contrib.trace :as trace]
            [robert.hooke :as hook])
  (:use [clojure.contrib.core :only [-?>]]))

(defn trace-fn-call-hook
  "Traces a single call to a function f with args.  'name' is the
  symbol name of the function."
  [f & args]  
  (let [id (gensym "TR")
        m (meta f)]
    (trace/tracer id (str (trace/trace-indent)
                          (pr-str (cons
                                   (symbol (str (:ns m)) (str (:name m)))
                                   args))))
    (let [[value err] (binding [trace/*trace-depth* (inc trace/*trace-depth*)]
                        (try [(apply f args) nil]
                             (catch Exception e [e e])))]
      (trace/tracer id (str (trace/trace-indent) "=> " (pr-str value)))
      (when err (throw err))
      value)))

(defn trace-fn-call
  "Traces a single call to a function f with args.  'name' is the
  symbol name of the function."
  [name f args]
  (let [id (gensym "t")]
    (trace/tracer id (str (trace/trace-indent) (pr-str (cons name args))))
    (let [[value err] (binding [trace/*trace-depth* (inc trace/*trace-depth*)]
                        (try [(apply f args) nil]
                             (catch Exception e [e e])))]
      (trace/tracer id (str (trace/trace-indent) "=> " (pr-str value)))
      (when err (throw err))
      value)))

(defn trace [v]
  (if-not (some #{trace-fn-call-hook} (-?> (deref v) meta :robert.hooke/hook deref))
    (hook/add-hook v trace-fn-call-hook)))

(defn untrace [v]
  (hook/remove-hook v trace-fn-call-hook))

(defn with-all-in-ns [f & namespaces]
  (doseq [namespace namespaces]
    (require namespace)
    (doseq [[_ v] (ns-interns namespace)]
      (if (fn? (deref v))
        (f v)))))


(defn all-fn-in-ns [ & namespaces]
  (for [namespace namespaces
        [k v] (ns-interns namespace)
        :when (fn? (deref v))]
    (symbol (str namespace) (str k))))

(defmacro dotrace-all [nslist fnlist excludelist & forms]
  `(binding [trace/trace-fn-call trace-fn-call]
     (trace/dotrace
     ~(vec (remove (set excludelist)
                   (concat (mapcat all-fn-in-ns nslist) fnlist))) ~@forms)))
