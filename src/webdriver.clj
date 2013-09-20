(ns webdriver
  (:require [clj-webdriver.element :refer [element-like?]]
            [slingshot.slingshot :refer [throw+]]
            [clj-webdriver.taxi :as browser]
            [clj-webdriver.core :as core]))

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
  ([loc] (move-to browser/*driver* loc))
  ([driver loc]
     (core/move-to-element driver (browser/element loc))
     (ajax-wait)))

(defn move-off
  ([loc] (move-off browser/*driver* loc))
  ([driver loc]
     (core/move-to-element driver (browser/element loc) -20 -20)))

(defn key-up
  ([loc k] (key-up browser/*driver* loc k))
  ([driver loc k]
     (core/key-up driver (browser/element loc) k)))

(defn text-present? [text]
  (.contains (browser/page-source) text))


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

;; remove print-method for webdriver due to https://github.com/semperos/core/issues/105

(remove-method print-method org.openqa.selenium.WebDriver)
(remove-method print-method org.openqa.selenium.WebElement)

(defmacro alias-all
  "Alias all the vars of taxi in this ns"
  []
  `(do ~@(for [[k v] (ns-publics 'clj-webdriver.taxi)]
           `(def ~k (deref ~v)))))

(alias-all)

(defn throw-element-not-found [q]
  (throw+ {:type ::element-not-found
           :query q}))

(defmacro with-element [[s q] & body]
  `(let [~s (browser/element ~q)]
     (if ~s
       (do ~@body)
       (throw-element-not-found ~q))))

(defn click
  "Click the first element found with query `q`.

   Examples:
   =========

   (click \"a#foo\")"
  ([q] (click clj-webdriver.taxi/*driver* q))
  ([driver q]
     (with-element [e q]
       (core/move-to-element driver e)
       (core/click e)
       (ajax-wait))))


(defn select-deselect
  "Set the selected state of element matching query `q` to boolean `b` (true = selected). Otherwise, do nothing and just return the element found.

   Examples:
   =========

   (select-deselect \"input.already-selected\" true) ;=> do nothing
   (select-deselect \"input.not-selected\" false)     ;=> do nothing
   (select-deselect \"input.not-selected\" true) ;=> click"
  ([q b] (select-deselect clj-webdriver.taxi/*driver* q b))
  ([driver q b]
     (with-element [e q]
       (core/move-to-element driver e)
       ((if b core/select core/deselect) e)
       (ajax-wait))))

(defn select
  "If the first form element found with query `q` is not selected, click the element to select it. Otherwise, do nothing and just return the element found.

   Examples:
   =========

   (select \"input.already-selected\") ;=> do nothing
   (select \"input.not-selected\")     ;=> click"
  ([q] (select clj-webdriver.taxi/*driver* q))
  ([driver q]
     (select-deselect driver q true)))

(defn deselect
  "If the first form element found with query `q` is selected, click the element to deselect it. Otherwise, do nothing and just return the element found.

   Examples:
   =========

   (select \"input.already-selected\") ;=> click
   (select \"input.not-selected\")     ;=> do nothing"
  ([q] (deselect clj-webdriver.taxi/*driver* q))
  ([driver q]
     (select-deselect driver q false)))

(defn select-by-text
  "Select the option element with visible text `text` within the first select list found with query `q`.

   Examples:
   =========

   (select-by-text \"#my-select-list\" \"foo\")"
  ([q text] (select-by-text clj-webdriver.taxi/*driver* q text))
  ([driver q text]
     (with-element [e q]
       (core/move-to-element driver e)
       (core/select-by-text e text)
       (ajax-wait))))

(defn hover "Hover over the specified element"
  ([q] (hover clj-webdriver.taxi/*driver* q))
  ([driver q]
     (core/move-to-element driver (element q))
     (ajax-wait)))

(defn input-text
  "Type the string `s` into the first form element found with query `q`.

   Examples:
   =========

   (input-text \"input#login_field\" \"semperos\")"
  ([q s] (input-text clj-webdriver.taxi/*driver* q s))
  ([driver q s]
     (with-element [e q]
       (core/move-to-element driver e)
       (core/clear e)
       (core/input-text e s)
       (ajax-wait))))

(defn select-deselect-by-text
  ([q b text] (select-deselect-by-text clj-webdriver.taxi/*driver* q b text))
  ([driver q b text]
     (with-element [e q]
       (core/move-to-element driver e)
       ((if b core/select-by-text core/deselect-by-text) e text)
       (ajax-wait))))

(defn quick-fill
  "A utility for filling out multiple fields in a form in one go. Always returns nil instead of the affected elements, since on submit all of the elements will be void.

   `items`   - a seq of queries to actions (queries find HTML elements, actions are fn's that act on them). Nil values are ignored.

   Note that an \"action\" that is just a String will be interpreted as a call to `input-text` with that String for the target text field.

   Examples:
   =========

   (quick-fill {\"#first_name\" \"Rich\" ; don't care about order
                \"a.foo\" click})
   (quick-fill [\"#first_name\" \"Rich\" ; enforce order
                \"a.foo\" click]"
  [items]
  (let [ordered-items (if (sequential? items)
                        (partition 2 items)
                        items)]
    (doseq [[q v] ordered-items]
      (when v
        (let [action (if (string? v)
                       #(input-text % v)
                       v)
              target-els (browser/elements q)]
          (if-not (seq target-els)
            (throw-element-not-found q))
          (doseq [el target-els]
            (action el)))))))
