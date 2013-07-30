(ns katello.activation-keys
  (:require [katello :as kt]
            (katello [navigation :as nav]
                     [notifications :as notification]
                     [ui-common :as common]
                     [ui :as ui]
                     [rest :as rest]
                     [tasks :refer [when-some-let] :as tasks])
            [clojure.data :as data]
            [clj-webdriver.taxi :as browser]
            [webdriver :as wd]))

;; Locators

(ui/defelements :katello.deployment/any []
  {::new                     "new"
   ::name-text               {:name "activation_key[name]"}
   ::description-text        {:name "activation_key[description]"}
   ::content-view-select     {:name "activation_key[content_view_id]"}
   ::save                    "save_key"
   ::create                  {:name "commit"}
   ::system-group-select     (ui/third-level-link "activation_keys_menu_system_groups")
   ::add-sys-group-form      "//form[@id='add_group_form']/button"
   ::add-sys-group           "//input[@id='add_groups']"
   ::system-groups           (ui/third-level-link "system_mgmt")
   ::applied-subscriptions   (ui/third-level-link "applied_subscriptions")
   ::available-subscriptions (ui/third-level-link "available_subscriptions")
   ::add-subscriptions       "//input[@id='subscription_submit_button']"            
   ::remove-link             (ui/remove-link "activation_keys")
   ::release-version-text    {:name "system[releaseVer]"}})

(wd/template-fns
 {subscription-checkbox "//a[.='%s']/../span/input[@type='checkbox']"
  sysgroup-checkbox "//input[@title='%s']"
  applied-subscriptions "xpath=(//table[@class='filter_table']//a[contains(@href, 'providers') or contains(@href, 'subscriptions')])[%s]"})

;; Nav

(nav/defpages :katello.deployment/any katello.menu
  [::page
   [::named-page (fn [activation-key] (nav/choose-left-pane activation-key))
    [::system-group-menu (nav/browser-fn (click ::system-groups)) ;; change this back to mouseover when possible
     [::system-group-page (nav/browser-fn (click ::system-group-select))]]]
   [::new-page (nav/browser-fn (click ::new))]])

;; Tasks

(defn- create
  "Creates an activation key with the given properties. Description is
   optional."
  [{:keys [name description content-view env] :as ak}]
  (nav/go-to ::new-page ak)
  (browser/click (ui/environment-link (:name env)))
  (browser/quick-fill-submit {::name-text (or name "")}
                             {::description-text (or description "")}
                             {::content-view-select (or (:published-name content-view) "")}
                             {::create browser/click})
  (notification/success-type :ak-create))

(defn- delete
  "Deletes the given activation key."
  [ak]
  (nav/go-to ak)
  (browser/click ::remove-link)
  (browser/click ::ui/confirmation-yes)
  (notification/success-type :ak-destroy))

(defn- add-subscriptions
  "Add subscriptions to activation key."
  [subscriptions]
  (browser/click ::available-subscriptions)
  (doseq [subscription subscriptions]
    (browser/click (subscription-checkbox subscription)))
  (browser/click ::add-subscriptions)
  (notification/success-type :ak-add-subscriptions))

(defn- remove-subscriptions [subscriptions]
  ;;TODO
  )
(defn- associate-system-group
  "Asscociate activation key to selected sytem group"
  [sg]
  (wd/->browser (click ::system-group-select)
                (click ::add-sys-group-form)
                (click (sysgroup-checkbox (:name sg)))
                (click ::add-sys-group))
  (notification/success-type :ak-add-sysgrps))

(defn get-subscriptions "Get applied susbscription info from activation key"
  [ak]
  (nav/go-to ak)
  (browser/click ::applied-subscriptions)
  (common/extract-list applied-subscriptions))

(defn- update [ak updated]
  (let [[remove add] (data/diff ak updated)]
    (when (some not-empty [remove add])
      (nav/go-to ak)
      (when-some-let [name (:name add)
                      description (:description add)] 
                     (common/in-place-edit {::name-text name
                                            ::description-text description}))
      (when-some-let [cv (:content-view add)
                      env (:env add)]
                     
                     (browser/quick-fill-submit {::content-view-select cv}
                                                {nav/select-environment-widget env}
                                                {::save browser/click}))
      (when-let [sg (:system-group add)]
        (associate-system-group sg))
      (when-let [subs (:subscriptions add)]
        (add-subscriptions subs))
      (when-let [subs (:subscriptions remove)]
        (remove-subscriptions subs)))))

(extend katello.ActivationKey
  ui/CRUD {:create create
           :delete delete
           :update* update}

  rest/CRUD (let [id-url (partial rest/url-maker [["api/activation_keys/%s" [identity]]])
                  query-url (partial rest/url-maker [["api/organizations/%s/activation_keys" [#'katello/org]]])]
              {:id rest/id-field
               :query (partial rest/query-by-name query-url)
               :read (partial rest/read-impl id-url)
               :create (fn [ak]
                         (merge ak
                                (rest/http-post
                                 (rest/url-maker [["api/environments/%s/activation_keys" [#'katello/env]]] ak)
                                 {:body {:activation_key (select-keys ak [:name :content-view :description])}})))})
  
  tasks/Uniqueable tasks/entity-uniqueable-impl
  nav/Destination {:go-to (partial nav/go-to ::named-page)})
