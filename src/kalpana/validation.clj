(ns kalpana.validation
  (:require [clojure.string :as string])
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
          :name-must-not-contain-trailing-whitespace #"Name must not contain trailing white spaces."
          :name-must-not-contain-characters #"Name cannot contain characters other than"
          :name-must-be-unique-within-org #"Name must be unique within one organization"
          :login-credential.username-cant-be-blank #"Login credential(\.username)? can't be blank"
          :login-credential.password-cant-be-blank #"Login credential(\.password)? can't be blank"}
         (cant-be-blank-errors [:name
                                :repository-url])))

(defn matching-validation-errors "Returns a set of matching known validation errors"
  [message]
  (set (filter (fn [k] (re-find (validation-errors k) message)) (keys validation-errors))))

(defn validator-error-dispatch "A custom dispatcher for error.handler
that matches when the handler's type matches any of the validation
errors from the UI."
  [err handler-meta]
  (and (= :validation-failed (:type err))
       (some #{(:type handler-meta)} (matching-validation-errors (:msg err)))))

(defn field-validation [create-fn expected-error]
  (let [message-after-create (with-handlers-dispatch validator-error-dispatch
                               [(handle expected-error [e] (:type e))]
                               (create-fn))]
    (verify (= message-after-create expected-error))))

(defn duplicate_disallowed [create-fn & {:keys [expected-error] :or {expected-error :name-taken-error}}]
  (create-fn)
  (field-validation create-fn expected-error))

(defn name-field-required [create-fn]
  (field-validation create-fn :name-cant-be-blank))

