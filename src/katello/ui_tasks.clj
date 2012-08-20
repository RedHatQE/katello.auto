(ns katello.ui-tasks
  (:require [katello.locators :as locators]
            [ui.navigate :as nav]
            [clojure.string :as string])
  (:use [com.redhat.qe.auto.selenium.selenium
         :only [connect browser ->browser fill-form fill-item
                loop-with-timeout]]
        katello.tasks
        [katello.conf :only [config]]
        [katello.api-tasks :only [when-katello when-headpin]]
        [slingshot.slingshot :only [throw+ try+]]
        [tools.verify :only [verify-that]]
        [clojure.string :only [capitalize]]
        [clojure.set :only [union]])
  (:import [com.thoughtworks.selenium SeleniumException]
           [java.text SimpleDateFormat]))

(declare search)


;;UI tasks

(defn errtype
  "Creates a predicate that matches a caught UI error of the given
   type (see known-errors). Use this predicate in a slingshot 'catch'
   statement. If any of the error types match (in case of multiple
   validation errors), the predicate will return true. Uses isa? for
   comparison, so hierarchies will be checked.
   example (try+ (dothat) (catch (errtype ::validation-error) _ nil))"
  [t]
  (with-meta
    (fn [e] (some #(isa? % t) (:types e)))
    {:type :serializable.fn/serializable-fn
     :serializable.fn/source `(errtype ~t)}))

(def ^{:doc "Navigates to a named location in the UI. The first
  argument should be a keyword for the place in the page tree to
  navigate to. The 2nd optional argument is a mapping of keywords to
  strings, if any arguments are needed to navigate there.
  Example: (navigate :named-organization-page {:org-name 'My org'})
  See also katello.locators/page-tree for all the places that can be
  navigated to."
       :arglists '([location-kw & [argmap]])}
  navigate (nav/nav-fn #'locators/page-tree))

(defn fill-ajax-form
  "Fills in a web form and clicks the submit button. Only waits for
   ajax calls to complete. Items should be a map, where the keys are
   locators for form elements, and values are the values to fill in.
   Submit should be a locator for the form submit button."
  [items submit]
  (fill-form items submit (constantly nil)))


;; Load peripherals
(load "ui_tasks/notifications")
(load "ui_tasks/changesets")
(load "ui_tasks/organizations")
(load "ui_tasks/environments")
(load "ui_tasks/providers")
(load "ui_tasks/roles")
(load "ui_tasks/users")


(defn activate-in-place
  "For an in-place edit input, switch it from read-only to editing
   mode. Takes the locator of the input in editing mode as an
   argument."
  [loc]
  (browser click (locators/inactive-edit-field loc)))

(defn in-place-edit
  "Fill out a form that uses in-place editing. Takes a map of locators
   to values. Each item will be activated, filled in and saved, and
   checks for success notification. Returns all the success
   notifications (or nil if notthing was changed)."
  [items] 
  (doall (for [[loc val] items]
           (if-not (nil? val)
             (do (activate-in-place loc)
                 (fill-item loc val)
                 (browser click :save-inplace-edit)
                 (check-for-success))))))

(defn extract-list [f]
  "Extract a list of items from the UI, accepts locator function as
   argument for example, extract-left-pane-list
   locators/left-pane-field-list"
  (let [elems (for [index (iterate inc 1)]
                (f (str index)))]
    (doall (take-while identity (for [elem elems]
                                  (try (browser getText elem)
                                       (catch SeleniumException e nil)))))))
 
(defn extract-left-pane-list []
  (extract-list locators/left-pane-field-list))

{"Library" ["prod1" "prod2" "prod3"]
 "Development" ["prod1" "prod2"]
 "QA" ["prod2"]}

{"prod1" ["Library" "Development" "QA"]
 "prod2" ["Library" "Development"]
 "prod3" ["Library"]}

(defn clear-search []
  (->browser (click :search-menu)
             (click :search-clear-the-search)))

(defn search
  "Search for criteria in entity-type, scope not yet implemented.
  if with-favorite is specified, criteria is ignored and the existing
  search favorite is chosen from the search menu. If add-as-favorite
  is true, use criteria and save it as a favorite, and also execute
  the search."
  [entity-type & [{:keys [criteria scope with-favorite add-as-favorite]}]]
  (navigate (entity-type {:users :users-page 
                          :organizations :manage-organizations-page
                          :roles :roles-page
                          :subscriptions :redhat-subscriptions-page
                          :gpg-keys :gpg-keys-page
                          :sync-plans :sync-plans-page
                          :systems  :systems-all-page
                          :system-groups :system-groups-page
                          :activation-keys :activation-keys-page
                          :changeset-promotion-history :changeset-promotion-history-page}))
  (if with-favorite
    (->browser (click :search-menu)
               (click (locators/search-favorite with-favorite)))
    (do (browser type :search-bar criteria)
        (when add-as-favorite
          (->browser (click :search-menu)
                     (click :search-save-as-favorite)))
        (browser click :search-submit)))
  (check-for-error {:timeout-ms 2000}))

(def sync-messages {:ok "Sync complete."
                    :fail "Error syncing!"})

(defn sync-complete-status
  "Returns final status if complete. If sync is still in progress, not
  synced, or queued, returns nil."
  [product]
  (some #{(browser getText (locators/provider-sync-progress product))}
        (vals sync-messages)))

(defn sync-success? "Returns true if given sync result is a success."
  [res]
  (= res (:ok sync-messages)))


(defn sync-repos
  "Syncs the given list of repositories. Also takes an optional
  timeout (in ms) of how long to wait for the sync to complete before
  throwing an error.  Default timeout is 2 minutes."
  [repos & [{:keys [timeout]}]]
  (navigate :sync-status-page)
  (doseq [repo repos]
    (browser check (locators/provider-sync-checkbox repo)))
  (browser click :synchronize-now)
  (browser sleep 10000)
  (zipmap repos (for [repo repos]
                     (loop-with-timeout (or timeout 120000) []
                       (or (sync-complete-status repo)
                           (do (Thread/sleep 10000)
                               (recur)))))))

(defn edit-system
  "Edits the properties of the given system. Optionally specify a new
  name, a new description, and a new location."
  [name {:keys [new-name description location release-version]}]
  (navigate :named-systems-page {:system-name name})
  (in-place-edit {:system-name-text-edit new-name
                  :system-description-text-edit description
                  :system-location-text-edit location
                  :system-release-version-select release-version}))

(defn subscribe-system
  "Subscribes the given system to the products. (products should be a
  list). Can also set the auto-subscribe for a particular SLA.
  auto-subscribe must be either true or false to select a new setting
  for auto-subscribe and SLA. If auto-subscribe is nil, no changes
  will be made."
  [{:keys [system-name add-products remove-products auto-subscribe sla]}]
  (navigate :system-subscriptions-page {:system-name system-name})
  (when-not (nil? auto-subscribe)
    (in-place-edit {:system-service-level-select (format "Auto-subscribe %s, %s"
                                                         (if auto-subscribe "On" "Off")
                                                         sla)}))
  (let [sub-unsub-fn (fn [content checkbox-fn submit]
                       (when-not (empty? content)
                         (doseq [item content]
                           (browser check (checkbox-fn item)))
                         (browser click submit)) )]
    (sub-unsub-fn add-products locators/subscription-available-checkbox :subscribe)
    (sub-unsub-fn remove-products locators/subscription-current-checkbox :unsubscribe))
  (check-for-success))

(def syncplan-dateformat (SimpleDateFormat. "MM/dd/yyyy"))
(def syncplan-timeformat (SimpleDateFormat. "hh:mm aa"))
(defn date-str [d] (.format syncplan-dateformat d))
(defn time-str [d] (.format syncplan-timeformat d))

(defn- split-date [{:keys [start-date start-date-literal start-time-literal]}]
  (list (if start-date (date-str start-date) start-date-literal)
        (if start-date (time-str start-date) start-time-literal)))

(defn create-sync-plan
  "Creates a sync plan with the given properties. Either specify a
  start-date (as a java.util.Date object) or a separate string for
  start-date-literal 'MM/dd/yyyy', and start-time-literal 'hh:mm aa'
  The latter can also be used to specify invalid dates for validation
  tests."
  [{:keys [name description interval start-date
           start-date-literal start-time-literal] :as m}]
  (navigate :new-sync-plan-page)
  (let [[date time] (split-date m)]
    (fill-ajax-form {:sync-plan-name-text name
                     :sync-plan-description-text description
                     :sync-plan-interval-select interval
                     :sync-plan-time-text time
                     :sync-plan-date-text date}
                    :save-sync-plan)
    (check-for-success)))

(defn edit-sync-plan
  "Edits the given sync plan with optional new properties. See also
  create-sync-plan for more details."
  [name {:keys [new-name
                description interval start-date start-date-literal
                start-time-literal] :as m}]
  (navigate :named-sync-plan-page {:sync-plan-name name})
  (let [[date time] (split-date m)]
    (in-place-edit {:sync-plan-name-text new-name
                    :sync-plan-description-text description
                    :sync-plan-interval-select interval
                    :sync-plan-time-text time
                    :sync-plan-date-text date})))

(defn sync-schedule
  "Schedules the given list of products to be synced using the given
  sync plan name."
  [{:keys [products plan-name]}]
  (navigate :sync-schedule-page)
  (doseq [product products]
    (browser click (locators/schedule product)))
  (browser click (locators/sync-plan plan-name))
  (browser clickAndWait :apply-sync-schedule )
  (check-for-success))

(defn current-sync-plan
  "Returns a map of what sync plan a product is currently scheduled
  for. nil if UI says 'None'"
  [product-names]
  (navigate :sync-schedule-page)
  (zipmap product-names
          (replace {"None" nil}
                   (doall (for [product-name product-names]
                            (browser getText (locators/product-schedule product-name)))))))

(defn create-activation-key
  "Creates an activation key with the given properties. Description
  and system-template are optional."
  [{:keys [name description environment system-template] :as m}]
  (navigate :new-activation-key-page)
  (browser click (locators/environment-link environment))
  (fill-ajax-form {:activation-key-name-text name
                   :activation-key-description-text description
                   :activation-key-template-select system-template}
                  :save-activation-key)
  (check-for-success))

(defn delete-activation-key
  "Deletes the given activation key."
  [name]
  (navigate :named-activation-key-page {:activation-key-name name})
  (browser click :remove-activation-key)
  (browser click :confirmation-yes)
  (check-for-success))

(defn upload-subscription-manifest
  "Uploads a subscription manifest from the filesystem local to the
   selenium browser. Optionally specify a new repository url for Red
   Hat content- if not specified, the default url is kept. Optionally
   specify whether to force the upload."
  [file-path & [{:keys [repository-url]}]]
  (navigate :redhat-subscriptions-page)
  (when-not (browser isElementPresent :choose-file)
    (browser click :import-manifest))
  (when repository-url
    (in-place-edit {:redhat-provider-repository-url-text repository-url}))
  (fill-ajax-form {:choose-file file-path}
                  :upload)
  (check-for-success {:timeout-ms 300000 :refresh? true})) ;using asynchronous notification until the bug https://bugzilla.redhat.com/show_bug.cgi?id=842325 gets fixed.
  ;(check-for-success))
  
(defn manifest-already-uploaded?
  "Returns true if the current organization already has Red Hat
  content uploaded."
  []
  (navigate :redhat-repositories-page)
  (browser isElementPresent :subscriptions-items))

(defn create-template
  "Creates a system template with the given name and optional
  description."
  [{:keys [name description]}]
  (navigate :new-system-template-page)
  (fill-ajax-form {:template-name-text name
                   :template-description-text description}
                  :save-new-template)
  (check-for-success))

(defn add-to-template
  "Adds content to a given template.  Example:
   (add-to-template 'mytemplate' [{:product 'prod3'
                                   :packages ['rpm1' 'rpm2']}
                                  {:product 'prod6'
                                   :repositories ['x86_64']}]"
  [template content]
  (navigate :named-system-template-page {:template-name template})
  (let [add-item (fn [item] (locators/toggle locators/template-toggler item true))]
    (doseq [group content]
      (let [category-keyword (-> group (dissoc :product) keys first)
            category-name (-> category-keyword
                             name
                             (.replace "-" " ")
                             capitalize-all)]
        (->browser
         (getEval "window.onbeforeunload = function(){};") ;circumvent popup
         (sleep 2000)
         (click (locators/template-product (:product group)))
         (sleep 2000)
         (click (locators/template-eligible-category category-name)))
        (doall (map add-item (group category-keyword)))
        (browser click :template-eligible-home)))
    (browser click :save-template)
    (check-for-success)))

(defn enable-redhat-repositories
  "Enable the given list of repos in the current org."
  [repos]
  (navigate :redhat-repositories-page)
  (doseq [repo repos]
    (browser check (locators/repo-enable-checkbox repo))))

(defn assign-user-default-org-and-env 
  "Assigns a default organization and environment to a user"
  [username org-name env-name]
  (navigate :user-environments-page {:username username})
  (browser select :user-default-org-select org-name)
  (browser click (locators/environment-link env-name))
  (browser click :save-user-environment)
  (check-for-success))

(defn create-gpg-key [name & [{:keys [filename contents]}]]
  (assert (not (and filename contents))
          "Must specify one one of :filename or :contents.")
  (assert (string? name))
  (navigate :new-gpg-key-page)
  (if filename
    (fill-ajax-form {:gpg-key-name-text name
                     :gpg-key-file-upload-text filename}
                    :gpg-key-upload-button)
    (fill-ajax-form {:gpg-key-name-text name
                     :gpg-key-content-text contents}
                    :gpg-keys-save))
  (check-for-success))
 

(defn remove-gpg-key 
  "Deletes existing GPG keys"
  [gpg-key-name]
  (navigate :named-gpgkey-page {:gpg-key-name gpg-key-name})
  (browser click :remove-gpg-key )
  (browser click :confirmation-yes)
  (check-for-success))

(defn sync-and-promote [products from-env to-env]
  (let [all-prods (map :name products)
        all-repos (apply concat (map :repos products))
        sync-results (sync-repos all-repos {:timeout 600000})]
        (verify-that (every? (fn [[_ res]] (sync-success? res))
                             sync-results))
        (promote-content from-env to-env {:products all-prods})))

(defn create-package-filter [name & [{:keys [description]}]]
  "Creates new Package Filter"
  (assert (string? name))
  (navigate :new-package-filter-page)
    (fill-ajax-form {:new-package-filter-name  name
                     :new-package-filter-description description}
                     :save-new-package-filter)
  (check-for-success))

(defn remove-package-filter 
  "Deletes existing Package Filter"
  [package-filter-name]
  (navigate :named-package-filter-page {:package-filter-name package-filter-name})
  (browser click :remove-package-filter-key )
  (browser click :confirmation-yes)
  (check-for-success))

(defn create-system
  "Creates a system"
   [name & [{:keys [sockets system-arch]}]]
   (navigate :new-system-page)
   (fill-ajax-form {:system-name-text name
                    :system-sockets-text sockets
                    :system-arch-select (or system-arch "x86_64")}
                    :create-system)
   (check-for-success))

(defn create-system-group
  "Creates a system-group"
   [name & [{:keys [description]}]]
   (navigate :new-system-groups-page)
   (fill-ajax-form {:system-group-name-text name
                    :system-group-description-text description}
                    :create-system-groups)
   (check-for-success))

(defn add-to-system-group
  "Adds a system to a System-Group"
   [system-group system-name]
   (navigate :named-system-group-page {:system-group system-group})
   (fill-ajax-form {:system-groups-hostname-toadd system-name}
                    :system-groups-add-system))

(defn copy-system-group
  "Clones a system group, given the name of the original system group
   to clone, and the new name and description."
  [orig-name new-name & [{:keys [description]}]]
  (navigate :named-system-group-page {:system-group orig-name})
  (browser click :system-group-copy)
  (fill-ajax-form {:system-group-copy-name-text new-name
                   :system-group-copy-description-text description}
                  :system-group-copy-submit)
  (check-for-success))

(defn remove-system-group [system-group & [{:keys [also-remove-systems?]}]]
  (navigate :named-system-group-page {:system-group system-group})
  (browser click :system-group-remove)
  (browser click :confirmation-yes)
  (browser click (if also-remove-systems?
                   :confirmation-yes
                   :system-group-confirm-only-system-group))
  (check-for-success))

(defn edit-system-group "Change the value of limit field in system group"
  [sg-name {:keys [new-limit new-sg-name description]}]
  (navigate :system-group-details-page {:system-group-name sg-name})
  (let [needed-flipping (and new-limit
                            (not= (= new-limit :unlimited)
                                  (browser isChecked :system-group-unlimited)))]
    (if (and new-limit (not= new-limit :unlimited))
      (do (browser uncheck :system-group-unlimited)
          (fill-ajax-form {:system-group-limit-value (str new-limit)}
                          :save-new-limit ))
      (browser check :system-group-unlimited))
    (when needed-flipping (check-for-success)))
  (in-place-edit {:system-group-name-text new-sg-name
                  :system-group-description-text description}))

(defn extract-content-search-results
  "Gets the content search results from the current page"
  []
  (extract-list locators/content-search-result-item-n))
