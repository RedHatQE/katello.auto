(ns katello.trace
  (:require [robert.hooke :as hook])
  (:use [clojure.contrib.core :only [-?>]]))

(def
 ^{:doc "Current stack depth of traced function calls."}
 *trace-depth* 0)

(defn tracer
  "This function is called by trace.  Prints to standard output, but
  may be rebound to do anything you like.  'name' is optional."
  [name value]
  (println (str (when name (format "%6s: " name))  value)))

(defn per-thread-tracer []
  (let [tracefile-name (str (.getName (Thread/currentThread)) ".trace")]
                                (fn [name value]
                                  (let [s (str (when name (format "%6s: " name))  value "\n")]
                                    (spit tracefile-name s
                                          :append true)))))
(defn trace
  "Sends name (optional) and value to the tracer function, then
  returns value.  May be wrapped around any expression without
  affecting the result."
  ([value] (trace nil value))
  ([name value]
     (tracer name (pr-str value))
     value))

(defn trace-indent
  "Returns an indentation string based on *trace-depth*"
  []
  (apply str (take *trace-depth* (repeat "|    "))))

(defn trace-fn-call
  "Traces a single call to a function f with args.  'name' is the
  symbol name of the function."
  [name f args]
  (let [id (gensym "t")]
    (tracer id (str (trace-indent) (pr-str (cons name args))))
    (let [[value err] (binding [*trace-depth* (inc *trace-depth*)]
                        (try [(apply f args) nil]
                             (catch Exception e [e e])))]
      (tracer id (str (trace-indent) "=> " (pr-str value)))
      (when err (throw err))
      value)))

(defmacro deftrace
  "Use in place of defn; traces each call/return of this fn, including
  arguments.  Nested calls to deftrace'd functions will print a
  tree-like structure."
  [name & definition]
  `(do
     (def ~name)
     (let [f# (fn ~@definition)]
       (defn ~name [& args#]
         (trace-fn-call '~name f# args#)))))

(defmacro dotrace
  "Given a sequence of function identifiers, evaluate the body
   expressions in an environment in which the identifiers are bound to
   the traced functions.  Does not work on inlined functions,
   such as clojure.core/+"
  [fnames & exprs]
  `(binding [~@(interleave fnames
                           (for [fname fnames]
                             `(let [f# @(var ~fname)]
                                (fn [& args#]
                                  (trace-fn-call '~fname f# args#)))))]
     ~@exprs))

(defn trace-fn-call-hook
  "Traces a single call to a function f with args.  'name' is the
  symbol name of the function."
  [f & args]  
  (let [id (gensym "TR")
        m (meta f)]
    (tracer id (str (trace-indent)
                          (pr-str (cons
                                   (symbol (str (:ns m)) (str (:name m)))
                                   args))))
    (let [[value err] (binding [*trace-depth* (inc *trace-depth*)]
                        (try [(apply f args) nil]
                             (catch Exception e [e e])))]
      (tracer id (str (trace-indent) "=> " (pr-str value)))
      (when err (throw err))
      value)))

(comment (defn trace [v]
           (if-not (some #{trace-fn-call-hook} (-?> (deref v) meta :robert.hooke/hook deref))
             (hook/add-hook v trace-fn-call-hook)))

         (defn untrace [v]
           (hook/remove-hook v trace-fn-call-hook)))

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

(defmacro dotrace-all [{:keys [namespaces fns exclude]} & forms]
  `(dotrace
   ~(vec (remove (set exclude)
                 (concat (mapcat all-fn-in-ns namespaces) fns))) ~@forms))


