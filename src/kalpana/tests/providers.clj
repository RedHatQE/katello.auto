(ns kalpana.tests.providers
  (:require [kalpana.tasks :as tasks]
            [kalpana.validation :as validate])
  (:import [org.testng.annotations Test])
  (:use [test-clj.testng :only [gen-class-testng data-driven]]
        [error.handler :only [with-handlers handle ignore]]
        [com.redhat.qe.verify :only [verify]]))

(defn ^{Test {:groups ["providers"]}} create_simple [_]
  (let [result-message (tasks/create-provider
                        (tasks/timestamp "auto-cp")
                        "my description"
                        :redhat
                        "http://myrepo.url.com/blah/")]
    (verify (string? result-message))))

(defn ^{Test {:groups ["providers"]}} delete_simple [_]
  (let [cp-name (tasks/timestamp "auto-cp-delete")]
    (tasks/create-provider
     cp-name
     "my description"
     :redhat
     "http://myrepo.url.com/blah/")
    (tasks/verify-success #(tasks/delete-provider cp-name))))

(defn validate [name description repo-url type  expected-result]
  (let [name (if (fn? name) (name) name)] ; timestamping at compile time defeats purpose of unique names
    (validate/field-validation       
     (fn []                           
       (tasks/create-provider name description type repo-url) 
       :success) expected-result)))

(def vdata (vec (concat
                 [[nil "blah" "http://sdf.com" :redhat :name-cant-be-blank]
                    
                  ^{Test {:groups ["blockedByBug-703528"]
                          :description "Test that invalid URL is rejected."}}
                  [#(tasks/timestamp "mytestcp") "blah" "@$#%$%&%*()[]{}" :redhat :kalpana-error]

                  [#(tasks/timestamp "mytestcp2") "blah" nil :redhat :repository-url-cant-be-blank]
                  [#(tasks/timestamp "mytestcp3") nil "http://sdf.com" :redhat :success]
                  [#(tasks/timestamp "mytestcp4") nil "http://sdf.com" :custom :success]]
                 (validate/variations
                  [#(tasks/timestamp "mytestcp5") :javascript "http://sdf.com" :custom :success])
                 (validate/variations                  
                  [:trailing-whitespace nil  "http://sdf.com" :custom  :name-must-not-contain-trailing-whitespace])
                 (validate/variations
                  [:invalid-character nil "http://sdf.com" :custom :name-must-not-contain-characters]))))

(data-driven validate {Test {:groups ["providers"]}} vdata)

(gen-class-testng)
