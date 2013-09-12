(ns webdriver
  (:use [clj-webdriver.element :only [element-like?]])
  (:require [clj-webdriver.taxi :as browser]
            [clj-webdriver.core :as clj-web]))

(declare my-driver)

(defprotocol SeleniumLocatable
  (sel-locator [x]))

(defn locator-args
  "If any args are keywords, look them up via
SeleniumLocatable protocol (which should return a selenium String
locator). Returns the args list with those Strings in place of the
keywords."
  [& args]
  (for [arg args]
    (if (keyword? arg) 
      (or (sel-locator arg)
          (throw (IllegalArgumentException.
                  (str "Locator " arg " not found in UI mapping."))))
      arg)))

(def ^{:doc "custom snippet that checks both jQuery and angular"}
  jquery+angular-ajax-finished
  "var errfn = function(f,n) { try { return f(n) } catch(e) {return 0}};
   return errfn(function(n) { return jQuery.active }) +
   errfn(function(n) { return angular.element('.ng-scope').injector().get('$http').pendingRequests.length });")

(def js-toggle-hidden
  "var tags = document.getElementsByClassName(arguments[0]);
    for (var i = 0; i < tags.length; i++) {
        tags[i].style.visibility = 'visible'
    }")

(def js-click
  "var tag = document.getElementById(arguments[0]);
")

(defn ajax-wait
  []
  (browser/wait-until #(= (browser/execute-script jquery+angular-ajax-finished) 0) 60000 1000))

(defn locator-finder-fn 
  ([q] (locator-finder-fn browser/*driver* q))
  ([driver q]
     (println (str "Q: " q))
     (ajax-wait)
     (let [loc (if (keyword? q)
                 (first (locator-args q))
                 q)]
       (cond  (map? loc) (browser/find-elements driver loc)
              (= "//" (subs loc 0 2)) (browser/xpath-finder loc)
              (re-matches #"xpath.*" loc) (browser/xpath-finder loc)
              :else (browser/find-elements driver {:id loc})))))

(def ^{:doc "A function to format locators out of a template. Example:
              ((template \"//div[.='%s']\") \"foo\") =>
                \"//div[.='foo']\""}
  template (partial partial format))

(defmacro template-fns
  "Expands into a function definition for each entry in m, where the
  key is a symbol for the function, and the value is a format string.
  When called, the function will format the its arguments with the
  format string."
  [m]
  `(do ~@(for [[sym fmt] m]
           `(def ~sym 
              (template ~fmt)))))

(defn new-local-driver
  "returns a local selenium webdriver instance.
Default browser-spec: firefox"
  ([] (new-local-driver {:browser :firefox}))
  ([browser-spec] (browser/new-driver browser-spec)))

(defn connect "Create a new selenium instance." [driver url]
  ([url] (connect (new-local-driver) url))
  ([driver url] )
  (def ^:dynamic my-driver driver)
  (browser/set-driver! my-driver)
  (browser/set-finder! locator-finder-fn))

(defmacro brow
  [action & args]
  `(~(symbol (str "browser/" action)) ~@args))

(defmacro ->browser
  [ & forms]
  `(do ~@(for [form forms] `(brow ~@form))))

(defmacro loop-with-timeout
  "Similar to clojure.core/loop, but adds a timeout to break out of
  the loop if it takes too long. timeout is in ms. bindings are the
  bindings that would be provided to clojure.core/loop. body is the
  loop body to execute if the timeout has not been reached. timeout-body
  is the body to execute if the timeout has been reached. timeout-body
  defaults to throwing a RuntimeException."
  [timeout bindings body & [timeout-body]]
  `(let [starttime# (System/currentTimeMillis)]
     (loop ~bindings
       (if  (> (- (System/currentTimeMillis) starttime#) ~timeout)
         ~(or timeout-body `(throw (RuntimeException. (str "Hit timeout of " ~timeout "ms."))))
         ~body))))

(defn move-to
  [driver loc]
  (clj-web/move-to-element driver loc))

(defn move-off
  [driver loc]
  (clj-web/move-to-element driver loc -5 0))

(defn move-to-and-jiggle
  [loc]
  (move-to browser/*driver* (browser/element loc))
  (move-off browser/*driver* (browser/element loc))
  (move-to browser/*driver* (browser/element loc)))

(defn key-up
  [driver loc k]
  (clj-web/key-up driver loc  k))

(defn text-present?
  [text]
  (.contains (browser/page-source) text))

(defn move-to-and-click
  [driver loc]
  (move-to driver loc)
  (browser/click loc))

(defmacro with-remote-driver-fn
  "Given a `browser-spec` to start a browser and a `finder-fn` to use as a finding function, execute the forms in `body`, then call `quit` on the browser.

   Examples:
   =========

   ;;
   ;; Log into Github
   ;;
   (with-driver {:browser :firefox} xpath-finder
     (to \"https://github.com\")
     (click \"//a[text()='Login']\")

     (input-text \"//input[@id='login_field']\" \"your_username\")
     (-> \"//input[@id='password']\"
       (input-text \"your_password\")
       submit))"
  [browser-spec finder-fn & body]
  `(binding [*driver* (core/new-driver ~browser-spec)
             *finder-fn* ~finder-fn]
    (try
      ~@body
      (finally
        (quit)))))
