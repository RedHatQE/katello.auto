(ns katello.roles
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            [clojure.data :as data]
            katello
            (katello [navigation :as nav]
                     [notifications :as notification] 
                     [tasks :as tasks]
                     [ui :as ui]
                     [rest :as rest]
                     [ui-common :as common])))

;; Locators

(ui/defelements :katello.deployment/any [katello.ui]
  {::new                             "//a[@id='new']"
   ::name-text                       "role[name]"
   ::description-text                "role[description]"
   ::save                            "role_save"
   ::users                           "role_users"
   ::permissions                     "role_permissions"
   ::next                            "next_button"
   ::previous                        "previous_button"
   ::permission-resource-type-select "permission[resource_type_attributes[name]]"
   ::permission-verb-select          "permission[verb_values][]"
   ::permission-tag-select           "permission[tag_values][]"        
   ::permission-name-text            "permission[name]"
   ::permission-description-text     "permission[description]"
   ::save-permission                 "save_permission_button"
   ::remove                          "remove_role"
   ::add-permission                  "add_permission"
   ::all-types                       "all_types"
   ::slide-link-home                 "//span[@id='roles']"
   ::all-verbs                       "all_verbs"
   ::all-tags                        "all_tags"}
  )

(sel/template-fns
 {permission-org "//li[@class='slide_link' and starts-with(normalize-space(.),'%s')]"
  role-action    "//li[.//span[@class='sort_attr' and .='%2$s']]//a[.='%s']"})

;; Nav

(nav/defpages :katello.deployment/any katello.menu
  [::page
   [::named-page (fn [role] (nav/choose-left-pane role))
    [::named-users-page (nav/browser-fn (click ::users))]
    [::named-permissions-page (nav/browser-fn (click ::permissions))]]])


;; Vars

(def administrator (katello/newRole {:name "Administrator"}))

;; Tasks

(def user-role-toggler (ui/toggler ui/add-remove role-action))

(defn- create
  "Creates a role with the given name and optional description."
  [{:keys [name description]}]
  (nav/go-to ::page)
  (browser click ::new)
  (sel/fill-ajax-form {::name-text name
                       ::description-text description}
                      ::save)
  (notification/check-for-success {:match-pred
                                   (notification/request-type? :roles-create)}))

(defn- goto-org-perms [org]
  (browser click ::permissions)
  (sel/->browser (click (permission-org (:name org)))
                 (sleep 1000)))

(defn- add-permissions [permissions]
  (browser click ::slide-link-home)
  (doseq [{:keys [name org description resource-type verbs tags]} permissions]
    (goto-org-perms org)
    (browser click ::add-permission)
    (if (= resource-type :all)
      (browser click ::all-types)
      (do (browser select ::permission-resource-type-select resource-type)
          (browser click ::next)
          (cond 
           (and (not verbs) (browser isVisible ::all-verbs)) 
           (browser click ::all-verbs)
                          
           (not (nil? verbs))
           (doseq [verb verbs]
             (browser addSelection ::permission-verb-select verb)))
          (browser click ::next)
          (cond 
           (and (not tags) (browser isVisible ::all-tags)) 
           (browser click ::all-tags)
                          
           (not (nil? tags))
           (doseq [tag tags]
             (browser addSelection ::permission-tag-select tag)))))
    (sel/fill-ajax-form {::permission-name-text name
                         ::permission-description-text description}
                        ::save-permission)
    (notification/check-for-success {:match-pred
                                     (notification/request-type? :roles-create-permission)})))
    

(defn- remove-permissions [permissions]
  (browser click ::slide-link-home)
  (doseq [{:keys [name org]} permissions]
    (goto-org-perms org)
    (browser click (user-role-toggler name false))
    (notification/check-for-success {:match-pred
                                     (notification/request-type? :roles-destroy-permission)})
    (browser sleep 5000)))

(defn- edit
  "Edits a role to add new permissions, remove existing permissions,
  and assign users to the role. Example:

  (edit-role 'myrole'
             {:add-permissions [{:resource-type 'Organizations'
                                 :verbs ['Read Organization']
                                 :name 'newPerm1'}]
              :remove-permissions ['existingPerm1' 'existingPerm2']
              :users ['joe' 'bob']})"
  [role updated]
  
  (let [[to-remove to-add _] (data/diff role updated)
        users-to-add (:users to-add)
        users-to-remove (:users to-remove)]
    (when (some not-empty (list to-remove to-add))
      (nav/go-to role))
    (when (or users-to-add users-to-remove)
      (browser click ::users)
      (doseq [user users-to-add]
        (common/toggle user-role-toggler (:name user) true))
      (doseq [user users-to-remove]
        (common/toggle user-role-toggler (:name user) false)))

    (add-permissions (:permissions to-add))
    (remove-permissions (:permissions to-remove))))

(defn- delete
  "Deletes the given role."
  [role]
  (nav/go-to role)
  (browser click ::remove)
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success {:match-pred (notification/request-type? :roles-destroy)}))


(extend katello.Role
  ui/CRUD {:create create
           :update* edit
           :delete delete}
  tasks/Uniqueable tasks/entity-uniqueable-impl
  nav/Destination {:go-to (partial nav/go-to ::named-page)})

(extend katello.Permission
  tasks/Uniqueable tasks/entity-uniqueable-impl)

