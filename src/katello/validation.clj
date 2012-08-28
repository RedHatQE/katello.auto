(ns katello.validation
  (:refer-clojure :exclude [fn])
  (:require [clojure.string :as string]
            [slingshot.slingshot :refer :all]
            [katello.tasks :refer [expecting-error]]
            [katello.ui-tasks :refer [errtype]]
            [serializable.fn :refer [fn]] 
            [tools.verify :refer [verify-that]]))


;; Types of bad data

(def trailing-whitespace-strings [ "abc123 ", " ", "abc  1-2-3   "]) 
(def javascript-strings          ["<script type=\"text/javascript\">document.write('<b>Hello World</b>'); </script>"])
(def invalid-character-strings   [".", "#", "   ]", "xyz%123", "123 abc 5 % b", "+abc123"])
(def invalid-urls                ["@$#%$%&%*()[]{}" "https://" "http" "https://blah.com:5:6:7/abc" "http:///" ""])


;; Named types of validation errors 

(def duplicate-disallowed (errtype :katello.notifications/name-taken-error))
(def name-field-required (errtype :katello.notifications/name-cant-be-blank))

(defmacro expecting-error-2nd-try
  "Executes body twice, the 2nd time will catch any error that matches
  pred. Note that body will almost certainly contain side effects, so
  care should be taken not to do something twice that you only intend
  to do once. For example, if body generates a timestamped value, the
  2nd execution will use a new, different timestamped value. See also
  katello.tasks/expecting-error."
  [pred & body]
  `(do ~@body
       (expecting-error ~pred ~@body)))
