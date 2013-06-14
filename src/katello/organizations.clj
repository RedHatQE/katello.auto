(ns katello.organizations
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser ->browser]]
            [ui.navigate :as navlib :refer [nav-tree]]
            [slingshot.slingshot :refer [try+ throw+]]
            katello
            (katello [navigation :as nav]
                     [ui :as ui]
                     [rest :as rest]
                     [tasks :as tasks]
                     [ui-common :as common]
                     [notifications :as notification]
                     [conf :refer [*session-org*]])
            [test.assert :as assert])
  (:import [com.thoughtworks.selenium SeleniumException]))

;; Locators

(ui/defelements :katello.deployment/any []
  {::new                    "//a[@id='new']"
   ::create                 "commit"
   ::name-text              "organization[name]"
   ::label-text             "organization[label]"
   ::description-text       "organization[description]"
   ::environments           (ui/link "Environments")
   ::edit                   (ui/link "Edit")
   ::remove                 (ui/link "Remove Organization")
   ::initial-env-name-text  "environment_name"
   ::initial-env-label-text "environment_label"
   ::initial-env-desc-text  "environment_description"
   ::org-switcher-row       "//div[@id='orgbox']//div[contains(@class, 'row') and position()=2]"
   ::default                "//ul[@id='organizationSwitcher']//span[@title='This is your default organization.']/../a"

   ;; System Default Info
   ::system-default-info      (ui/third-level-link "org_system_default_info")
   ::distributor-default-info (ui/third-level-link "org_distributor_default_info")
   ::keyname-text             "new_default_info_keyname"
   ::create-keyname           "add_default_info_button"
   ::apply-default-info       "apply_default_info_button"
   ::disabled-apply-btn       "//input[@class='btn fullwidth']"})

(sel/template-fns
 {org-switcher-row   "//ul[@id='organizationSwitcher']//input[contains(@value,'%s')]/../a"
  remove-keyname-btn "//input[contains(@data-id, 'default_info_%s')]"})
;; Nav

(nav/defpages :katello.deployment/any katello.menu
  [::page 
   [::new-page (nav/browser-fn (click ::new))]
   [::named-page (fn [ent] (nav/choose-left-pane (katello/org ent)))
    [::system-default-info-page (nav/browser-fn (click ::system-default-info))]
    [::distributor-default-info-page (nav/browser-fn (click ::distributor-default-info))]]])

;; Tasks

(defn label-filler
  "Fills in the label field which has special js sauce that prevents
   it from being writable unless the name field has been blurred."
  [name-loc label-loc label-text]
  (when label-text
    (->browser (fireEvent name-loc "blur")
               (ajaxWait)
               (setText label-loc "")
               (setText label-loc label-text))))

(defn isKeynamePresent?
  "Checks whether a keyname is present in the organization's custom fields."
  [keyname]
  (boolean (get (common/extract-custom-keyname-list) keyname)))


(defn add-custom-keyname
  "Adds a custom keyname field to an organization and optionally apply it to existing systems"
  [org section keyname & [{:keys [apply-default]}]]
  (nav/go-to section org)
  ;; Make sure the 'Add' button is disabled
  (assert (= (get (browser getAttributes ::create-keyname) "disabled") ""))
  (->browser (setText ::keyname-text keyname)
             (keyUp ::keyname-text "w")
             (click ::create-keyname))
  (if apply-default
    (do
      (browser click ::apply-default-info)
      (browser click ::ui/confirmation-yes)
      (browser waitForElement ::disabled-apply-btn "120000")))
  (notification/check-for-success))

(defn remove-custom-keyname
  "Removes custom keyname field from an organization"
  [org section keyname]
  (nav/go-to section org)
  (browser click (remove-keyname-btn keyname))
  (notification/check-for-success))

(defn- create
  "Creates an organization with the given name and optional description."
  [{:keys [name label description initial-env]}]
  (nav/go-to ::new-page)
  (browser setText ::name-text name)
  (browser sleep 1000)
  (sel/fill-ajax-form [label-filler [::name-text ::label-text label]
                       ::description-text description
                       ::initial-env-name-text (:name initial-env)
                       label-filler [::initial-env-name-text ::initial-env-label-text (:label initial-env)]
                       ::initial-env-desc-text (:description initial-env)]
                      ::create)
  (notification/success-type :org-create))

(defn- delete
  "Deletes an organization."
  [org]
  (nav/go-to org)
  (browser click ::remove)
  (browser click ::ui/confirmation-yes)
  (notification/success-type :org-destroy) ;queueing success
  (browser refresh)
  (notification/check-for-success {:timeout-ms (* 20 60 1000) :match-pred (notification/request-type? :org-destroy)})) ;for actual delete

(defn- update
  "Edits an organization. Currently the only property of an org that
   can be edited is the org's description."
  [org {:keys [description]}]
  (nav/go-to org)
  (common/in-place-edit {::description-text (:description description)}))

(extend katello.Organization
  ui/CRUD {:create create
           :update* update
           :delete delete}
  
  rest/CRUD (let [uri "api/organizations/"
                  label-url (partial rest/url-maker [[(str uri "%s") [identity]]])]
              {:id rest/label-field
               :query (fn [e] (rest/query-by-name (constantly (rest/api-url uri)) e))
               :create (fn [org]
                         (merge org (rest/http-post (rest/api-url uri)
                                                    {:body (select-keys org [:name :description])})))

               ;; orgs don't have an internal id, they just use :label, so we can't tell whether it exists
               ;; in katello yet or not.  So try to read, and throw ::rest/entity-not-found if not present
               :read (fn [org]
                       (try+ (rest/http-get (label-url org))
                             (catch [:status 404] _
                               (throw+ {:type ::rest/entity-not-found, :entity org})))) 
              
               :update* (fn [org new-org]
                          (rest/http-put (label-url org)
                                         {:body {:organization (select-keys new-org [:description])}}))
               :delete (fn [org]
                         (rest/http-delete (label-url org)))})
  tasks/Uniqueable  {:uniques (fn [{:keys [initial-env] :as org}]
                                (for [ts (tasks/timestamps)]
                                  (let [stamp-if-set (fn [s] (if (seq s) (tasks/stamp ts s) nil))
                                        updated-fields (-> org
                                                           (update-in [:name] (partial tasks/stamp ts))
                                                           (update-in [:label] stamp-if-set))]
                                    (if initial-env
                                      (update-in updated-fields [:initial-env :org :name]
                                                 #(when %1
                                                    (tasks/stamp ts %1)))
                                      updated-fields))))}

  nav/Destination {:go-to (partial nav/go-to ::named-page)})



(defn switch
  "Switch to the given organization in the UI. If no args are given,
   the value of *session-org* is used. If force? is true,switch even
   if the org switcher is already on the requested org. Optionally
   also select the default org for this user. To remove any default
   org for this user, set default org to :none. Using force is not
   necessary if also setting the default-org."
  ([] (switch *session-org*))
  ([{:keys [name]} & [{:keys [force? default-org]}]] 
     (when (or force? 
               default-org
               (not= (nav/current-org) name)) 
       (browser fireEvent ::ui/switcher "click")
       (browser ajaxWait)
       (when default-org
         (let [current-default (try (browser getText ::default)
                                    (catch SeleniumException _ :none))]
           (when (not= current-default (:name default-org))
             (browser click (ui/default-star (if (= default-org :none)
                                               current-default
                                               (:name default-org))))
             (notification/check-for-success))))
       (when name
         (browser clickAndWait (ui/switcher-link name))))))

(defn switcher-available-orgs
  "List of names of orgs currently selectable in the org dropdown."
  []
  (browser fireEvent ::ui/switcher "click")
  (browser sleep 1000)
  (doall (take-while identity
                     (for [i (iterate inc 1)]
                       (try (browser getText (org-switcher-row i))
                            (catch SeleniumException _ nil))))))
