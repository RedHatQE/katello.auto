(ns katello.ui-common
  (:require [clojure.data.json  :as json]
            [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]] 
            (katello [navigation :as nav]
                     [menu :as menu]
                     [ui :as ui]
                     [tasks         :refer :all] 
                     [notifications :as notification] 
                     [conf          :refer [config]] 
                     [api-tasks     :refer [when-katello when-headpin]]) 
            [slingshot.slingshot :refer [throw+ try+]]
            [test.assert         :as assert]
            [inflections.core    :refer [pluralize]])
  (:import [com.thoughtworks.selenium SeleniumException]
           [java.text SimpleDateFormat]))

;; Nav

(def pages #'menu/pages) ; alias 

;; Nav fns

(defn inactive-edit-field
  "Takes a locator for an active in-place edit field, returns the
  inactive version"
  [loc]
  (format "//div[@name='%1s']" (sel/sel-locator loc)))

(defn toggle "Toggles the item from on to off or vice versa."
  [a-toggler associated-text on?]
  (browser click (a-toggler associated-text on?)))

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
  (browser click (inactive-edit-field loc)))

(defn in-place-edit
  "Fill out a form that uses in-place editing. Takes a map of locators
   to values. Each item will be activated, filled in and saved, and
   checks for success notification. Returns all the success
   notifications (or nil if notthing was changed)."
  [items] 
  (doall (for [[loc val] items]
           (if-not (nil? val)
             (do (activate-in-place loc)
                 (sel/fill-item loc val)
                 (browser click ::ui/save-inplace-edit)
                 (notification/check-for-success))))))

(defn extract-list [f]
  "Extract a list of items from the UI, accepts locator function as
   argument for example, extract-left-pane-list
   left-pane-field-list"
  (let [elems (for [index (iterate inc 1)]
                (f (str index)))]
    (doall (take-while identity (for [elem elems]
                                  (try (browser getText elem)
                                       (catch SeleniumException e nil)))))))

(defn extract-left-pane-list []
  (extract-list ui/left-pane-field-list))

(defn clear-search []
  (sel/->browser (click ::ui/search-menu)
                 (click ::ui/search-clear-the-search)))

(defn search
  "Search for criteria in entity-type, scope not yet implemented.
  if with-favorite is specified, criteria is ignored and the existing
  search favorite is chosen from the search menu. If add-as-favorite
  is true, use criteria and save it as a favorite, and also execute
  the search."
  [entity-type & [{:keys [criteria scope with-favorite add-as-favorite]}]]
  (nav/go-to (entity-type {:users :katello.users/page 
                           :organizations :katello.organizations/page
                           :roles :katello.roles/page
                           :subscriptions :redhat-subscriptions-page
                           :gpg-keys :katello.gpg-keys/page
                           :sync-plans :katello.sync-management/plans-page
                           :systems  :katello.systems/page
                           :system-groups :katello.system-groups/page
                           :activation-keys :katello.activation-keys/page
                           :changeset-promotion-history :katello.changesets/history-page}))
  (if with-favorite
    (sel/->browser (click ::ui/search-menu)
                   (click (ui/search-favorite with-favorite)))
    (do (browser type ::ui/search-bar criteria)
        (when add-as-favorite
          (sel/->browser (click ::ui/search-menu)
                         (click ::ui/search-save-as-favorite)))
        (browser click ::ui/search-submit)))
  (notification/verify-no-error {:timeout-ms 2000}))




