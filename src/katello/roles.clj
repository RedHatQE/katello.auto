(ns katello.roles
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            (katello [navigation :as nav]
                     [notifications :as notification] 
                     [ui :as ui]
                     [ui-common :as common])))

;; Locators

(ui/deflocators
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
   ::all-verbs                       "all_verbs"
   ::all-tags                        "all_tags"}
  ui/locators)

(sel/template-fns
 {permission-org "//li[@class='slide_link' and starts-with(normalize-space(.),'%s')]"
  role-action    "//li[.//span[@class='sort_attr' and .='%2$s']]//a[.='%s']"})

;; Nav

(nav/defpages (common/pages)
  [::page
   [::named-page [role-name] (nav/choose-left-pane  role-name)
    [::named-users-page [] (browser click ::users)]
    [::named-permissions-page [] (browser click ::permissions)]]])


;; Tasks

(def user-role-toggler (ui/toggler ui/add-remove role-action))

(defn create
  "Creates a role with the given name and optional description."
  [name & [{:keys [description]}]]
  (nav/go-to ::page)
  (browser click ::new)
  (sel/fill-ajax-form {::name-text name
                       ::description-text description}
                      ::save)
  (notification/check-for-success {:match-pred
                                   (notification/request-type? :roles-create)}))


(defn edit
  "Edits a role to add new permissions, remove existing permissions,
  and assign users to the role. Example:

  (edit-role 'myrole'
             {:add-permissions [{:resource-type 'Organizations'
                                 :verbs ['Read Organization']
                                 :name 'newPerm1'}]
              :remove-permissions ['existingPerm1' 'existingPerm2']
              :users ['joe' 'bob']})"
  [name {:keys [add-permissions remove-permissions users]}]
  (let [nav (fn [page] (nav/go-to page {:role-name name}))
        each-org (fn [all-perms perms-fn]
                   (when all-perms
                     (nav ::named-permissions-page)
                     (doseq [{:keys [org permissions]} all-perms]
                       (sel/->browser (click (permission-org org))
                                      (sleep 1000))
                       (perms-fn permissions)
                       (browser click ::permissions))))] ;;go back up to choose next org
    (when users
      (nav ::named-users-page)
      (doseq [user users]
        (common/toggle user-role-toggler user true)))
    (each-org remove-permissions
              (fn [permissions]
                (doseq [permission permissions]
                  (browser click (user-role-toggler permission false))
                  (notification/check-for-success {:match-pred
                                                   (notification/request-type? :roles-destroy-permission)})
                  (browser sleep 5000))))
    (each-org add-permissions
              (fn [permissions]
                (doseq [{:keys [name description resource-type verbs tags]} permissions]
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
                                      ::save-permission))
                (notification/check-for-success {:match-pred
                                                 (notification/request-type? :roles-create-permission)})))))

(defn delete
  "Deletes the given role."
  [name]
  (nav/go-to ::named-page {:role-name name})
  (browser click ::remove)
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success {:match-pred (notification/request-type? :roles-destroy)}))


(defn validate-permissions-navigation
  "Validate Navigation of permissions page under Roles."
  [role-name perm-name org resource-type verbs tags]
  (nav/go-to ::named-permissions-page {:role-name role-name})
  (sel/->browser (click (permission-org org))
                 (sleep 1000))
  (browser click ::add-permission)
  (browser select ::permission-resource-type-select resource-type)
  (browser click ::next)
  (doseq [verb verbs]
    (browser addSelection ::permission-verb-select verb))
  (browser click ::next)
  (doseq [tag tags]
    (browser addSelection ::permission-tag-select tag))
  (sel/->browser (click ::next)
                 (setText ::permission-name-text perm-name)
                 (setText ::permission-description-text "myperm descriptions"))
  (while (browser isVisible ::previous)
    (browser click ::previous))  
  (while (not (browser isVisible ::save-permission))
    (browser click ::next))
  (browser click ::save-permission)
  (notification/check-for-success {:match-pred (notification/request-type? :roles-create-permission)}))