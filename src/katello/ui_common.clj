(ns katello.ui-common
  (:require [clojure.data.json  :as json]
            [webdriver :as browser]
            [katello :as kt]
            (katello [navigation :as nav]
                     [menu :as menu]
                     [ui :as ui]
                     [tasks         :refer :all]
                     [notifications :as notification]
                     [conf          :refer [config *session-org*]]
                     [rest     :refer [when-katello when-headpin]])
            [slingshot.slingshot :refer [throw+ try+]]
            [test.assert         :as assert])
  (:import [com.thoughtworks.selenium SeleniumException]
           [org.openqa.selenium NoSuchElementException]
           [java.text SimpleDateFormat]))

;; Nav fns

(defn inactive-edit-field
  "Takes a locator for an active in-place edit field, returns the
  inactive version"
  [loc]
  (dissoc (browser/sel-locator loc) :tag))

(defn toggle "Toggles the item from on to off or vice versa."
  [a-toggler associated-text on?]
  (browser/click (a-toggler associated-text on?)))

;;UI tasks

(defn errtype
  "Creates a predicate that matches a caught UI error of the given
   type (see known-errors). Use this predicate in a slingshot 'catch'
   statement. If any of the error types match (in case of multiple
   validation errors), the predicate will return true. Uses isa? for
   comparison, so hierarchies will be checked.
   example (try+ (dothat) (catch (errtype ::validation-error) _ nil))"
  [known-type]
  (with-meta
    (fn [obj]
      (boolean (some #(isa? % known-type)
                     (notification/matching-errors obj))))
    {:type :serializable.fn/serializable-fn
     :serializable.fn/source `(errtype ~known-type)}))

(defn activate-in-place
  "For an in-place edit input, switch it from read-only to editing
   mode. Takes the locator of the input in editing mode as an
   argument."
  [loc]
  (browser/click (inactive-edit-field loc)))

(defn in-place-edit
  "Fill out a form that uses in-place editing. Takes a map of locators
   to values. Each item will be activated, filled in and saved, and
   checks for success notification. Returns all the success
   notifications (or nil if notthing was changed)."
  [items]
  (doall (for [[loc val] items]
           (if-not (nil? val)
             (do (activate-in-place loc)
                 (browser/clear loc)
                 (browser/input-text loc val)
                 (browser/click ::ui/save-inplace-edit)
                 (notification/check-for-success))))))

(defn extract-list [f]
  "Extract a list of items from the UI, accepts locator function as
   argument for example, extract-left-pane-list
   left-pane-field-list"
  (let [elems (for [index (iterate inc 1)]
                (f (str index)))]
    (doall (take-while identity (for [elem elems]
                                  (try (browser/text elem)
                                       (catch NoSuchElementException e nil)))))))

(defn extract-left-pane-list []
  (nav/scroll-left-pane-until (constantly false))
  (map browser/text (browser/elements ::ui/left-pane-list)))

(defn extract-custom-keyname-list []
  (set (map browser/text (browser/elements ::ui/keyname-list))))

(defn extract-custom-value-list []
  (set (extract-list ui/custom-value-list)))

(defn clear-search []
  (browser/click ::ui/search-menu)
  (browser/click ::ui/search-clear-the-search))

(defn search
  "Search for criteria in a particular class of entity (eg, katello.Role), within a given org.
   Scope is not yet implemented.  if with-favorite is specified,
   criteria is ignored and the existing search favorite is chosen from
   the search menu. If add-as-favorite is true, use criteria and save
   it as a favorite, and also execute the search.

   Alternatively, pass in an mostly empty prototype record, as long as
   the class and org can be derived, eg (katello/newRole {:org myorg})"
  ([ent-class org {:keys [criteria scope with-favorite add-as-favorite]}]
     (nav/go-to ({katello.User :katello.users/page
                  katello.Organization :katello.organizations/page
                  katello.Role :katello.roles/page
                  katello.Subscription :redhat-subscriptions-page
                  katello.GPGKey :katello.gpg-keys/page
                  katello.SyncPlan :katello.sync-management/plans-page
                  katello.System  :katello.systems/page
                  katello.SystemGroup :katello.system-groups/page
                  katello.ActivationKey :katello.activation-keys/page
                  katello.Changeset :katello.changesets/history-page} ent-class)
                (or org *session-org*))
     (if with-favorite
       (do (browser/click ::ui/search-menu)
           (browser/click (ui/search-favorite with-favorite)))
       (do (browser/input-text ::ui/search-bar criteria)
           (when add-as-favorite
             (browser/click ::ui/search-menu)
             (browser/click ::ui/search-save-as-favorite))
           (browser/click ::ui/search-submit)))
     (notification/verify-no-error {:timeout-ms 2000}))
  ([proto-entity opts]
     (search (class proto-entity) (try (kt/org proto-entity)
                                       (catch IllegalArgumentException _ nil)) opts)))

#_(defn disabled?
    [locator]
    (let [all-attribs (browser/attribute locator)]
      (some true?
            (for [avail-attribs ["class" "disabled" "checked"]]
              (if (get all-attribs avail-attribs)
                (boolean (some #{"disabled" "checked"}
                               (clojure.string/split (get all-attribs avail-attribs) #" "))))))))

(defn disabled?
  [locator]
  (some #{"disabled" "checked"} (for [avail-attribs ["class" "disabled" "checked"]]
                                  (browser/attribute locator avail-attribs))))

(defn save-cancel [save-locator cancel-locator request-type input-locator requested-value save?]
  (let [inactive-elem (inactive-edit-field input-locator)
        orig-text (browser/text  inactive-elem)]
    (browser/click inactive-elem)
    (browser/input-text  input-locator requested-value)
    (if save?
      (do (browser/click save-locator)
          (notification/success-type request-type)
          (let [new-text (browser/text inactive-elem)]
            (when (not= new-text requested-value)
              (throw+ {:type ::save-failed
                       :requested-value requested-value
                       :new-value new-text
                       :msg "Input field didn't update properly after saving."}))))
      (do (browser/click cancel-locator)
          (let [new-text (browser/text inactive-elem)]
            (when (not= new-text orig-text)
              (throw+ {:type ::cancel-failed
                       :requested-value requested-value
                       :new-value new-text
                       :msg "Value changed even after clicking cancel button."})))))))

 (defn edit-sys-details
    [items]
    (doall (for [[loc val] items]
             (do (browser/clear loc)
               (browser/input-text loc val)
               (browser/click ::ui/save-button)))))
