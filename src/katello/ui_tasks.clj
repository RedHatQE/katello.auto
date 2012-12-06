(ns katello.ui-tasks
  (:require [clojure.data.json  :as json]
            
            [com.redhat.qe.auto.selenium.selenium
             :refer [browser ->browser fill-form fill-item]]
            (katello [navigation :as nav]
                     [locators      :as locators]
                     [tasks         :refer :all] 
                     [notifications :as notification] 
                     [conf          :refer [config]] 
                     [api-tasks     :refer [when-katello when-headpin]]) 
            [slingshot.slingshot :refer [throw+ try+]]
            [test.assert         :as assert]
            [inflections.core    :refer [pluralize]] 
            (clojure [string     :refer [capitalize replace-first]] 
                     [set        :refer [union]]
                     [string     :as string]))
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
                 (notification/check-for-success))))))

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
  (nav/go-to (entity-type {:users :users-page 
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
  (notification/verify-no-error {:timeout-ms 2000}))
 
(defn create-activation-key
  "Creates an activation key with the given properties. Description
  and system-template are optional."
  [{:keys [name description environment system-template] :as m}]
  (nav/go-to :new-activation-key-page)
  (browser click (locators/environment-link environment))
  (fill-ajax-form {:activation-key-name-text name
                   :activation-key-description-text description
                   :activation-key-template-select system-template}
                  :save-activation-key)
  (notification/check-for-success))

(defn delete-activation-key
  "Deletes the given activation key."
  [name]
  (nav/go-to :named-activation-key-page {:activation-key-name name})
  (browser click :remove-activation-key)
  (browser click :confirmation-yes)
  (notification/check-for-success))

(defn add-subscriptions-to-activation-key
  "Add subscriptions to activation key."
  [name subscriptions]
  (nav/go-to :named-activation-key-page {:activation-key-name name})
  (browser click :available-subscriptions)
  (doseq [subscription subscriptions]
    (browser click (locators/subscription-checkbox subscription)))
  (browser click :add-subscriptions-to-activation-key)
  (notification/check-for-success))
  
(defn enable-redhat-repositories
  "Enable the given list of repos in the current org."
  [repos]
  (nav/go-to :redhat-repositories-page)
  (doseq [repo repos]
    (browser check (locators/repo-enable-checkbox repo))))

(defn create-gpg-key [name & [{:keys [filename contents]}]]
  (assert (not (and filename contents))
          "Must specify one one of :filename or :contents.")
  (assert (string? name))
  (nav/go-to :new-gpg-key-page)
  (if filename
    (fill-ajax-form {:gpg-key-name-text name
                     :gpg-key-file-upload-text filename}
                    :gpg-key-upload-button)
    (fill-ajax-form {:gpg-key-name-text name
                     :gpg-key-content-text contents}
                    :gpg-keys-save))
  (notification/check-for-success))
 

(defn remove-gpg-key 
  "Deletes existing GPG keys"
  [gpg-key-name]
  (nav/go-to :named-gpgkey-page {:gpg-key-name gpg-key-name})
  (browser click :remove-gpg-key )
  (browser click :confirmation-yes)
  (notification/check-for-success))

(defn create-package-filter [name & [{:keys [description]}]]
  "Creates new Package Filter"
  (assert (string? name))
  (nav/go-to :new-package-filter-page)
    (fill-ajax-form {:new-package-filter-name  name
                     :new-package-filter-description description}
                     :save-new-package-filter)
  (notification/check-for-success))

(defn remove-package-filter 
  "Deletes existing Package Filter"
  [package-filter-name]
  (nav/go-to :named-package-filter-page {:package-filter-name package-filter-name})
  (browser click :remove-package-filter-key )
  (browser click :confirmation-yes)
  (notification/check-for-success))

