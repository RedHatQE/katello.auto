(ns ^{:author "Jeff Weiss"}
  kalpana.locators
  (:use [com.redhat.qe.auto.selenium.selenium :only [SeleniumLocatable]]
        [clojure.contrib.string :only [split join capitalize]])
  (:import [com.redhat.qe.auto.selenium Element LocatorTemplate]))

;;ui layer

(defn same-name "takes a collection of keywords like :registration-settings
and returns a mapping like :registration-settings -> 'Registration Settings'" 
  ([coll]
     (same-name identity identity coll))
  ([word-fn coll]
     (same-name word-fn identity coll))
  ([word-fn val-fn coll]
     (zipmap coll
	     (for [keyword coll]
               (->> keyword name (split #"-") (map word-fn) (join " ") val-fn)))))

(defmacro define-strategies
  "Create a function for each locator strategy in map m (which maps
  keyword to LocatorStrategy). Each function will be named the same as
  the keyword, take arguments and return a new element constructed
  with the locator strategy and args."
  [m]
  `(do ~@(for [loc-strat (keys m)]
           `(defn ~(symbol (name loc-strat)) [& args#]
              (Element. ~(m loc-strat) (into-array args#))))))

(define-strategies {:link (LocatorTemplate. "Link" "link=$1")
                    :tab (LocatorTemplate. "Tab" "link=$1")
                    :textbox (LocatorTemplate. "Text box" "xpath=//*[self::input[(@type='text' or @type='password') and @name='$1'] or self::textarea[@name='$1']]")})

(defn- tabs "creates mapping eg: {:my-tab 'link=My Tab'}"
  [keys]
  (same-name capitalize tab keys))

(def uimap (merge
            {:error-message "//div[@class='warning']"

             ;;stuff that is on more than one page
             :name-text (textbox "name")
             :description-text (textbox "description")

             ;; login page
             :username-text (textbox "username")
             :password-text (textbox "password")
             :log-in "commit"

             ;;main banner
             :search-textbox (textbox "s")
             :search-button "//button[@form='s']"
             
             ;;tabs with special chars in name
             :sub-organizations (tab "Sub-Organizations")

             ;;Organizations tab
             :new-organization (link "New Organization")
             :create-organization "//input[@name='commit' and @value='Create']"

             ;;Environments tab
             :prior-environment "//select[@id='environment_prior']"
             :create-environment "//input[@name='commit' and @value='Create']"}
             
            ;;regularly named tabs
            (tabs [:organizations
                   :administration
                   :systems
                   :content-management
                   :dashboard
                   :environments
                   :subscriptions
                   :create])))

(extend-protocol SeleniumLocatable
  clojure.lang.Keyword
  (sel-locator [k] (uimap k)))


