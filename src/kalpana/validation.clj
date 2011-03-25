(ns kalpana.validation
  (:use [error.handler :only [with-handlers handle ignore]]
        [com.redhat.qe.verify :only [verify]]))

(defn name-field-required [create-fn]
  (let [message-after-create (with-handlers [(handle :name-cant-be-blank [e] (:type e))]
                               (create-fn))]
    (verify (= message-after-create :name-cant-be-blank))))

(defn duplicate_disallowed [create-fn]
  (create-fn)
  (let [message-after-create (with-handlers
                               [(handle :name-taken-error [e] (:type e))]
                               (create-fn))]
    (verify (= message-after-create :name-taken-error))))
