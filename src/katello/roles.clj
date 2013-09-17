(ns katello.roles
  (:require [webdriver :as wd]
            [clj-webdriver.taxi :as browser]
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

(wd/template-fns
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
  (wd/click ::new)
  (browser/quick-fill-submit {::name-text name}
                             {::description-text description}
                             {::save wd/click})
  (notification/success-type :roles-create))

(defn- goto-org-perms [org]
  (wd/click ::permissions)
  (wd/->browser (click (permission-org (:name org))))
  (Thread/sleep 1000))

(defn- add-permissions [permissions]
  (wd/click ::slide-link-home)
  (doseq [{:keys [name org description resource-type verbs tags]} permissions]
    (goto-org-perms org)
    (wd/click ::add-permission)
    (if (= resource-type :all)
      (wd/click ::all-types)
      (do (wd/select-by-text ::permission-resource-type-select resource-type)
          (wd/click ::next)
          (cond 
           (and (not verbs) (browser/visible? ::all-verbs)) 
           (wd/click ::all-verbs)
                          
           (not (nil? verbs))
           (doseq [verb verbs]
             (wd/select-by-text  ::permission-verb-select verb)))
          (wd/click ::next)
          (cond 
           (and (not tags) (browser/visible? ::all-tags)) 
           (wd/click ::all-tags)
                          
           (not (nil? tags))
           (doseq [tag tags]
             (wd/select-by-text  ::permission-tag-select tag)))))
    (browser/quick-fill-submit {::permission-name-text name}
                               {::permission-description-text description}
                               {::save-permission wd/click})
    (notification/success-type :roles-create-permission)))
    

(defn- remove-permissions [permissions]
  (wd/click ::slide-link-home)
  (doseq [{:keys [name org]} permissions]
    (goto-org-perms org)
    (wd/click (user-role-toggler name false))
    (notification/success-type :roles-destroy-permission)
    (Thread/sleep 5000)))

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
      (wd/click ::users)
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
  (wd/click ::remove)
  (wd/click ::ui/confirmation-yes)
  (notification/success-type :roles-destroy))


(extend katello.Role
  ui/CRUD {:create create
           :update* edit
           :delete delete}

  rest/CRUD (let [url "api/roles"
                  url-by-id (partial rest/url-maker [["api/roles/%s" [identity]]])]
              {:id rest/id-field})
  
  tasks/Uniqueable tasks/entity-uniqueable-impl
  nav/Destination {:go-to (partial nav/go-to ::named-page)})

(extend katello.Permission
  tasks/Uniqueable tasks/entity-uniqueable-impl)

