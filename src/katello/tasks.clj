(ns katello.tasks
  (:require [slingshot.slingshot :refer :all] 
            [clojure.string :refer [split join capitalize]])
  (:import java.util.Date))

(defmacro ^{:see-also "https://github.com/scgilardi/slingshot"}
  expecting-error
  "Inverts exception handling. Execute forms, if error is caught
   matching selector, nil is returned. If no error is caught,
   an :success error is thrown. If a non-matching error occurs, the
   exception will not be caught and will propagate up the stack. You
   can still use this even in cases where a success is what you
   expect, just use the selector [:type :success]
   Examples: (expecting-error ArithmeticException (/ 1 0)) -> nil

             (expecting-error ArithmeticException (/ 12 3)) ->
               throws :unexpected-success exception.
    See also
   slingshot.slingshot/try+ for selector documentation"
  [selector & forms]
  `(try+ ~@forms
         (throw+ {:type :success :expected ~selector})
         (catch ~selector e# nil)))

(def date-format (java.text.SimpleDateFormat. "MMdd-HHmmss-SSS"))
 

(defn timestamps []
  "Returns an infinite lazy sequence of timestamps in ms, starting
  with the current time, incrementing the time by one on each
  successive item."
  (iterate inc (System/currentTimeMillis)))

(defn date-string
  "Formats a (long) timestamp ts as string using a date format"
  [ts]
  (.format date-format (Date. ts)))

(defprotocol Uniqueable
  (uniques [o] "Generates an infinite series of unique objects based on this one"))

(extend java.lang.String
  Uniqueable
  {:uniques (fn [s] (map (comp (partial format "%s-%s" s) date-string)
                         (timestamps)))})

(extend nil
  Uniqueable {:uniques (constantly (repeat nil))})

(defn timestamped-seq
  "Returns an infinite lazy sequence of timestamped values. f is a
   function to pass each timestamp (a long) to."
  [f]
  (map f (timestamps)))

(defn uniques-formatted
  "Returns an infinite lazy sequence of timestamped strings, uses s as
  a format string (should have only one %s in it)."
  [s]
  (timestamped-seq (comp (partial format s) date-string)))

(def ^{:doc "Returns an infinite lazy sequence of timestamped objects, uses s as
             the base string."}
  unique-names uniques)

(defn uniqueify [o]
  (first (uniques o)))

(defn stamp [ts s]
  (format "%s-%s" s (date-string ts)))

(defn stamp-entity
  "stamps entity name with timestamp s"
  [ent ts]
  (update-in ent [:name] (partial stamp ts)))

(def entity-uniqueable-impl
  {:uniques #(for [ts (timestamps)]
               (stamp-entity % ts))})


(def ^{:doc "Returns one unique string using s as the format string.
             Example: (unique-name 'joe-%s.zip') -> 'joe-12694956934.zip'"}
  unique-format (comp first uniques-formatted))

(defmacro with-unique
  "Binds variables to unique strings. Supports simple list
  destructuring. Example:
   (with-unique [[x y] 'foo' z 'bar'] [x y z]) will give something like:
     ['foo-12346748964356' 'foo-12346748964357' 'bar-12346748964358']"
  [bindings & forms]
  `(let ~(vec (apply concat
                 (for [[k v] (partition 2 bindings)]
                   (if (vector? k)
                     [k `(take ~(count k) (uniques ~v))]
                     [k `(uniqueify ~v)]))))
     ~@forms))

(defmacro with-unique-ent
  "macro-defining macro to create macros you can call
  like (with-unique-org o (create o))"
  [suffix-str base-ent-expr]
  `(defmacro ~(symbol (str "with-unique-" suffix-str))
     [sym# ~'& body#]
     `(with-unique [~sym# ~'~base-ent-expr]
        ~@body#)))

(defn uniqueify-vals
  "Uniquifies the values in map m"
  [m]
  (zipmap (keys m) (map uniqueify (vals m))))

(defn capitalize-all [s]
  (let [matcher (re-matcher #"(\S+)" s)
        buffer (new StringBuffer)]
    (while (.find matcher)
      (.appendReplacement matcher buffer (capitalize (.group matcher 1))))
    (.appendTail matcher buffer)
    (.toString buffer)))

(defn do-steps
  "Call all fs in order, with single argument m"
  [m & fs]
  ((apply juxt fs) m))

(def tmpdir (System/getProperty "java.io.tmpdir"))

(defn tmpfile
  "Given a filename or path, get a path to the file within the local system's temporary dir"
  [filename]
  (-> tmpdir
     (str "/" filename)
     (java.io.File.)
     (.getCanonicalPath)))

(defn random-string
  "Create a random string with characters in the range lo to hi (of
   default encoding) with given length"
  [lo hi length]
  (let [rand-range #(+ (rand-int (- hi lo)) lo)]
    (->> rand-range
       repeatedly
       (map char)
       (take length)
       (apply str))))

(defmacro when-some-let
  "When any of the bindings evaluate to logical true, evaluate body."
  [bindings & body]
  `(let ~bindings
     (when (or ~@(filter symbol?
                         (tree-seq coll? identity
                                   (keys (apply hash-map bindings)))))
       ~@body)))
