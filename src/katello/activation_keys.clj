(ns katello.activation-keys
  (:require [katello :as kt]
            (katello [navigation :as nav]
                     [notifications :as notification]
                     [ui-common :as common]
                     [ui :as ui]
                     [rest :as rest]
                     [tasks :refer [when-some-let] :as tasks])
            [clojure.data :as data]
            [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser ->browser]]))

;; Locators

(ui/deflocators {::new                     "new"
                 ::name-text               "activation_key[name]"
                 ::description-text        "activation_key[description]"
                 ::template-select         "activation_key[system_template_id]"
                 ::content-view-select     "activation_key[content_view_id]"
                 ::save                    "save_key"
                 ::system-group-select     (ui/menu-link "activation_keys_menu_system_groups")
                 ::add-sys-group-form      "//form[@id='add_group_form']/button"
                 ::add-sys-group           "//input[@id='add_groups']"
                 ::system-groups           (ui/menu-link "system_mgmt")
                 ::applied-subscriptions   (ui/menu-link "applied_subscriptions")
                 ::available-subscriptions (ui/menu-link "available_subscriptions")
                 ::add-subscriptions       "//input[@id='subscription_submit_button']"            
                 ::remove-link             (ui/remove-link "activation_keys")
                 ::release-version-text    "system[releaseVer]"})

(sel/template-fns
 {subscription-checkbox "//a[.='%s']/../span/input[@type='checkbox']"
   sysgroup-checkbox "//input[@title='%s']"
  applied-subscriptions "xpath=(//table[@class='filter_table']//a[contains(@href, 'providers') or contains(@href, 'subscriptions')])[%s]"})

;; Nav

(nav/defpages (common/pages)
  [::page
   [::named-page [activation-key] (nav/choose-left-pane activation-key)
    [::system-group-menu [] (browser mouseOver ::system-groups)
     [::system-group-page [] (browser click ::system-group-select)]]]
   [::new-page [] (browser click ::new)]])

;; Tasks

(defn create
  "Creates an activation key with the given properties. Description
  and system-template are optional."
  [{:keys [name description env system-template] :as ak}]
  (nav/go-to ::new-page {:org (kt/org ak)})
  (browser click (ui/environment-link (:name env)))
  (sel/fill-ajax-form {::name-text name
                       ::description-text description
                       ::template-select (:name system-template)}
                      ::save)
  (notification/check-for-success))

(defn delete
  "Deletes the given activation key."
  [ak]
  (nav/go-to ak)
  (browser click ::remove-link)
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success))

(defn- add-subscriptions
  "Add subscriptions to activation key."
  [subscriptions]
  (browser click ::available-subscriptions)
  (doseq [subscription subscriptions]
    (browser click (subscription-checkbox subscription)))
  (browser click ::add-subscriptions)
  (notification/check-for-success))

(defn- remove-subscriptions [subscriptions]
  ;;TODO
  )
(defn- associate-system-group
  "Asscociate activation key to selected sytem group"
  [sg]
  (->browser (click ::system-group-select)
             (click ::add-sys-group-form)
             (click (sysgroup-checkbox (:name sg)))
             (click ::add-sys-group)))

(defn get-subscriptions "Get applied susbscription info from activation key"
  [name]
  (nav/go-to ::named-page {:activation-key name})
  (browser click ::applied-subscriptions)
  (common/extract-list applied-subscriptions))

(defn update [ak updated]
  (let [[remove add] (data/diff ak updated)]
    (when (some not-empty [remove add])
      (nav/go-to ak)
      (when-some-let [name (:name add)
                      description (:description add)] 
                     (common/in-place-edit {::name-text name
                                            ::description-text description}))
      (when-some-let [st (:system-template add)
                      cv (:content-view add)
                      env (:env add)]
                     
                     (sel/fill-ajax-form {::template-select st
                                          ::content-view-select cv
                                          nav/select-environment-widget env}
                                         ::save))
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
               :read (partial rest/read-impl id-url)})
  
  tasks/Uniqueable tasks/entity-uniqueable-impl
  nav/Destination {:go-to #(nav/go-to ::named-page {:activation-key %1
                                                    :org (kt/org %1)})})
