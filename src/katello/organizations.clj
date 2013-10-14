(ns katello.organizations
  (:require [ui.navigate :as navlib :refer [nav-tree]]
            [webdriver :as browser]
            [katello.tests.useful :refer [third-lvl-menu-click]]
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
  (:import [org.openqa.selenium NoSuchElementException]))

;; Locators

(ui/defelements :katello.deployment/any []
  {::new                    "//a[@id='new']"
   ::create                 {:name "commit"}
   ::name-text              {:tag :input, :name "organization[name]"}
   ::label-text             {:tag :input, :name "organization[label]"}
   ::description-text       {:tag :textarea :name "organization[description]"}
   ::environments           (ui/link "Environments")
   ::edit                   (ui/link "Edit")
   ::remove                 (ui/link "Remove Organization")
   ::initial-env-name-text  {:tag :input, :name "environment[name]"}
   ::initial-env-label-text {:tag :input, :name "environment[label]"}
   ::initial-env-desc-text  {:tag :input, :name "environment[description]"}
   ::org-switcher-row       "//div[@id='orgbox']//div[contains(@class, 'row') and position()=2]"
   ::default                "//ul[@id='organizationSwitcher']//i[contains(@class,'icon-star') and not(contains(@class,'icon-star-empty'))]/../a"

   ;; System Default Info
   ::default-info             {:id "organization_default_info"}
   ::system-default-info      (ui/third-level-link "org_system_default_info")
   ::distributor-default-info (ui/third-level-link "org_distributor_default_info")
   ::keyname-text             {:id "new_default_info_keyname"}
   ::create-keyname           {:id "add_default_info_button"}
   ::apply-default-info       {:id "apply_default_info_button"}
   ::disabled-apply-btn       "//input[@class='btn fullwidth']"})

(browser/template-fns
 {org-switcher-row   "//ul[@id='organizationSwitcher']//input[contains(@value,'%s')]/../a"
  remove-keyname-btn "//input[contains(@data-id, 'default_info_%s')]"})
;; Nav

(nav/defpages :katello.deployment/any katello.menu
  [::page
   [::new-page (fn [_] (browser/click ::new))]
   [::named-page (fn [ent] (nav/choose-left-pane (katello/org ent)))
    [::default-info-menu (fn [_]
                           (Thread/sleep 2000)
                           (browser/move-to ::default-info))
     [::system-default-info-page (fn [_] (third-lvl-menu-click ::system-default-info))]
     [::distributor-default-info-page (fn [_] (third-lvl-menu-click ::distributor-default-info))]]]])

;; Tasks

(defn isKeynamePresent?
  "Checks whether a keyname is present in the organization's custom fields."
  [keyname]
  (contains? (common/extract-custom-keyname-list) keyname))


(defn add-custom-keyname
  "Adds a custom keyname field to an organization and optionally apply it to existing systems"
  [org section keyname & [{:keys [apply-default]}]]
  (nav/go-to section org)
  ;; Make sure the 'Add' button is disabled
  (assert (boolean (browser/attribute ::create-keyname :disabled)))
  (Thread/sleep 1000)
  (browser/input-text ::keyname-text keyname)
  (browser/click ::create-keyname)
  (if apply-default
    (do
      (browser/click ::apply-default-info)
      (browser/click ::ui/confirmation-yes)))
      ;;(browser/wait-until (browser/exists? ::disabled-apply-btn) "120000")))  ;;not required
  (notification/check-for-success))

(defn remove-custom-keyname
  "Removes custom keyname field from an organization"
  [org section keyname]
  (nav/go-to section org)
  (browser/click (remove-keyname-btn keyname))
  (notification/check-for-success))

(defn- create
  "Creates an organization with the given name and optional description."
  [{:keys [name label description initial-env]}]
  (nav/go-to ::new-page)
  (browser/ajax-wait)
  (browser/quick-fill [::name-text name
                       ::description-text description])
  (let [label-activate #(webdriver/execute-script "$('.name_input').trigger('blur')")] ;; workaround to activate label js
    (when label
      (label-activate) 
      (browser/clear ::label-text)
      (browser/input-text ::label-text label))
    (when (and (rest/is-katello?) initial-env)
      (browser/quick-fill [::initial-env-name-text (:name initial-env)
                           ::initial-env-desc-text (:description initial-env)])
      (when (:label initial-env)
        (label-activate)
        (browser/clear ::initial-env-label-text)
        (browser/input-text ::initial-env-label-text (:label initial-env)))))
  (nav/scroll-to-right-pane-item ::create)
  (browser/click ::create)
  (notification/success-type :org-create))

(defn- delete
  "Deletes an organization."
  [org]
  (nav/go-to org)
  (browser/click ::remove)
  (browser/click ::ui/confirmation-yes)
  (notification/success-type :org-destroy) ;queueing success
  (browser/refresh)
  (notification/check-for-success {:timeout-ms (* 20 60 1000) :match-pred (notification/request-type? :org-delete)})) ;for actual delete

(defn- update
  "Edits an organization. Currently the only property of an org that
   can be edited is the org's description."
  [org {:keys [description]}]
  (nav/go-to org)
  (common/in-place-edit {::description-text  description}))

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
                       (try+ (rest/read-impl (partial rest/url-maker [["api/organizations/%s" [identity]]]) org) 
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
       (browser/click (browser/find-element-under ::ui/switcher {:tag :a}))
       (browser/ajax-wait)
       (when default-org
         (let [default-org-name (when (not= default-org :none)
                                  (or (:name default-org)
                                      (throw+ {:type ::nil-org-name
                                               :msg "Can't set default org to an org with :name=nil"
                                               :org default-org})))
               current-default (try
                                 (if (browser/exists? ::default)
                                   (do (while (not (browser/visible? ::default))
                                         (nav/scroll-org-switcher))
                                       (browser/text ::default))
                                   nil)
                                 (catch NoSuchElementException _ nil))]
           (if (nil? default-org-name)
             (while (not (browser/visible? ::default))
               (nav/scroll-org-switcher))
             (when (not= current-default default-org-name)
               (while (not (browser/visible? (ui/switcher-link default-org-name)))
                 (nav/scroll-org-switcher))))
           (browser/click (ui/default-star (or default-org-name current-default)))
           (notification/check-for-success)
           (notification/flush)
           (Thread/sleep 5000)
           (browser/click (browser/find-element-under ::ui/switcher {:tag :a}))))
       (when name
         (while (not (browser/visible? (ui/switcher-link name)))
           (nav/scroll-org-switcher))
         (browser/click (ui/switcher-link name))))))

(defn switcher-available-orgs
  "List of names of orgs currently selectable in the org dropdown."
  []
  (browser/click (browser/find-element-under ::ui/switcher {:tag :a}))
  (Thread/sleep 1000)
  (->> (clj-webdriver.taxi/find-elements-under ::ui/switcher {:tag :a, :class "org-link"})
       (filter :webelement) ;; sel bug? when parent el is empty still returns '({:webelement nil})
       (map browser/text)
       doall))
