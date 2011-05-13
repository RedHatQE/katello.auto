(ns kalpana.validation
  (:require [clojure.string :as string]
            [clojure.contrib.logging :as log])
  (:use [error.handler :only [with-handlers with-handlers-dispatch handle ignore]]
        [com.redhat.qe.config :only [same-name]]
        [com.redhat.qe.verify :only [verify]]))

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
          :login-credential.username-cant-be-blank #"Login credential(\.username)? can't be blank"
          :login-credential.password-cant-be-blank #"Login credential(\.password)? can't be blank"}
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
  [message]
  (set (filter (fn [k] (re-find (validation-errors k) message)) (keys validation-errors))))

(defn field-validation [create-fn expected-error]
  (let [message-after-create (with-handlers
                               [(handle :validation-failed [e] (-> e :msg matching-validation-errors))]
                               (create-fn))] 
    (verify (or (= expected-error message-after-create)
                (some #{expected-error} message-after-create)))))

(defn duplicate_disallowed [create-fn & {:keys [expected-error] :or {expected-error :name-taken-error}}]
  (log/debug (str "Expecting error " expected-error ))
  (create-fn)
  (field-validation create-fn expected-error))

(defn name-field-required [create-fn]
  (field-validation create-fn :name-cant-be-blank))

