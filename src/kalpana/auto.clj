(ns kalpana.auto
  (:use [com.redhat.qe.config :only [property-map]]
        [com.redhat.qe.auto.selenium.selenium :only [connect browser SeleniumLocatable new-element]]
        [test-clj.testng :only [gen-class-testng]]
        [clojure.contrib.string :only [split join capitalize]]
        [error.handler :only [raise]])
  (:import [com.redhat.qe.auto.testng TestScript]
           [org.testng.annotations Test BeforeSuite]
           [com.redhat.qe.auto.selenium Element LocatorTemplate]))

;;config layer

(def kalpana-auto-properties {:server-url "kalpana.url"
                              :admin-user ["kalpana.admin.user" "admin"]
                              :admin-password ["kalpana.admin.password" "admin"]
                              :selenium-address ["selenium.address" "localhost:4444"]})

(def config (atom {}))

(defn init []
  (TestScript.)
  (swap! config merge (property-map kalpana-auto-properties)))

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
  "Create a function for each locator strategy in
  locator-strategies. Each function will take arguments and return a
  new element with that locator strategy and args."
  [m]
  `(do ~@(for [loc-strat (keys m)]
           `(defn ~(symbol (name loc-strat)) [& args#]
              (Element. ~(m loc-strat) (into-array args#))))))

(define-strategies {:link (LocatorTemplate. "Link" "link=$1")
                    :tab (LocatorTemplate. "Tab" "link=$1")
                    :textbox (LocatorTemplate. "Text box" "xpath=//*[self::input[(@type='text' or @type='password') and @name='$1'] or self::textarea[@name='$1']]")})

(defn- tab-links "creates mapping eg: {:my-tab 'link=My Tab'}"
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
            (tab-links [:organizations
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

;;tasks

(def known-errors {})

(defn matching-error "Returns a keyword of known error, if the message matches any of them."
  [message]
  (let [matches-message? (fn [key] (let [re (known-errors key)]
                                    (if (re-find re message) key false)))]
    (or (some matches-message? (keys known-errors))
	:kalpana-error)))

(defn check-for-error []
  (if (browser isElementPresent :error-message)
    (let [message (browser getText :error-message)]
      (raise :type (matching-error message) :msg message ))))

(defn navigate-to-tab [& tabs]
  (for [tab tabs] (browser clickAndWait tab)))

(defn create-organization [name description]
  (navigate-to-tab :organizations)
  (browser setText :name-text name)
  (browser setText :description-text description)
  (browser clickAndWait :create-organization))

(defn create-environment [org name description & {:keys [prior-env] :or {prior-env nil}}]
  (comment "currently no way to navigate here!" (navigate-to-tab :xyz))
  (browser setText :name-text name)
  (browser setText :description-text description)
  (if prior-env
    (browser select :prior-environment prior-env))
  (browser clickAndWait :create-organization))

(defn login [username password]
  (browser setText :username-text username)
  (browser setText :password-text password)
  (browser clickAndWait :log-in))

(defn ^{BeforeSuite {:groups ["setup"]}}
  start_selenium [_]
  (init)
  (let [sel-addr (@config :selenium-address)
        [host port] (split #":" sel-addr)] 
    (connect host (Integer/parseInt port) "" (@config :server-url))
    (browser start)
    (browser open (@config :server-url))))

(defn ^{Test {:groups ["login"]}}
  login_admin [_]
  (login (@config :admin-user) (@config :admin-password)))


(gen-class-testng)
