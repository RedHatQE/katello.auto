(ns katello.tasks
  (:require [slingshot.slingshot :refer :all] 
            [clojure.string :refer [split join capitalize]])
  (:import java.util.Date))

(def library "Library")

;;var for synchronizing promotion calls, since only one can be done in
;;the system at a time.
(def promotion-lock nil)


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


(defn unique-names
  "Returns an infinite lazy sequence of timestamped strings, uses s as
  the base string."
  [s]
  (for [t (timestamps)] (str s "-" (.format  date-format (Date. t)))))

(defn uniqueify
  "Returns one unique string using s as the base string.
   Example: (unique-name 'joe') -> 'joe-12694956934'"
  [s]
  (first (unique-names s)))

(defmacro with-unique
  "Binds variables to unique strings. Example:
   (with-unique [x 'foo' y 'bar'] [x y]) will give something like:
     ['foo-12346748964356' 'bar-12346748964357']"
  [bindings & forms]
  `(let ~(vec (apply concat
                 (for [[k v] (apply hash-map bindings)]
                   [k `(uniqueify ~v)])))
     ~@forms))

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

(defn chain-envs
  "Given a list of environments, return successive pairs (eg:
   envs ['a' 'b' 'c'] -> ('a' 'b'), ('b' 'c')"
  [envs]
  (partition 2 1 envs))
