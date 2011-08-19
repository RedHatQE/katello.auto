(ns katello.validation
  (:require [clojure.string :as string]
            [clojure.contrib.logging :as log])
  (:use [error.handler :only [with-handlers with-handlers-dispatch handle ignore]]
        [katello.tasks :only [success?]]
        [test.tree :only [print-meta]]
        [com.redhat.qe.config :only [same-name]]
        [com.redhat.qe.verify :only [verify-that]]))

(defn cant-be-blank-errors
  "Takes collection of keywords like :name and produces map entry like
:name-cant-be-blank #\"Name can't be blank"
  [coll]
  (same-name identity
             (comp re-pattern
                   string/capitalize
                   #(string/replace % " cant " " can't "))
             (map #(-> (name %) (str "-cant-be-blank") keyword)
                  coll)))

(def validation-errors
  (merge {:name-taken-error #"Name has already been taken"
          :name-no-leading-trailing-whitespace #"Name must not contain leading or trailing white space"
          :name-must-not-contain-characters #"Name cannot contain characters other than"
          :name-must-be-unique-within-org #"Name must be unique within one organization" 
          :only-one-redhat-provider-per-org #"Only one Red Hat provider permitted"
          :repository-url-invalid #"Repository url is invalid"
          :start-date-time-cant-be-blank #"Date and Time can't be blank"}
         (cant-be-blank-errors [:name
                                :repository-url])))

(def test-data {:trailing-whitespace [ "abc123 ", " ", "abc  1-2-3   "]
                :invalid-character [".", "#", "   ]", "xyz%123", "123 abc 5 % b", "+abc123"]
                :javascript [ "<script type=\"text/javascript\">document.write('<b>Hello World</b>'); </script>"]}) 

(defn variations [args]
  (let [t (first (filter (set (keys test-data)) args))]
    (vec (for [datum (test-data t)]
           (vec (replace {t datum} args))))))

(defn matching-validation-errors "Returns a set of matching known validation errors"
  [m]
  (set (filter (fn [k] (re-find (validation-errors k) (:msg m))) (keys validation-errors))))

(defn expect-error [expected-validation-err]
 (with-meta (fn [result]
              (some #{expected-validation-err} (:validation-errors result)))
   (print-meta `(~'expect-error ~expected-validation-err))) )

(defn field-validation [create-fn args pred]
  (let [results (with-handlers
                  [(handle :validation-failed [e]
                           (assoc e :validation-errors (matching-validation-errors e)))]
                  (apply create-fn args))] 
    (verify-that (pred results))))

(defn duplicate-disallowed [create-fn args & {:keys [pred] :or {pred (expect-error :name-taken-error)}}]
  (apply create-fn args)
  (field-validation create-fn args pred))

(defn name-field-required [create-fn args]
  (field-validation create-fn args (expect-error :name-cant-be-blank)))

