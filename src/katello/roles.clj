(ns katello.roles
  (:require [com.redhat.qe.auto.selenium.selenium :as sel]
            (katello [navigation :as nav]
                     [locators :as locators] 
                     [notifications :as notification] 
                     [ui-tasks :refer [fill-ajax-form navigate]]))
  )

;;
;; Roles
;;

;; Locators

(swap! locators/uimap merge
       {:new-role                        "//a[@id='new']"
        :new-role-name-text              "role[name]"
        :new-role-description-text       "role[description]"
        :save-role                       "role_save"
        :save-user-edit                  "save_password"
        :role-users                      "role_users"
        :role-permissions                "role_permissions"
        :next                            "next_button"
        :permission-resource-type-select "permission[resource_type_attributes[name]]"
        :permission-verb-select          "permission[verb_values][]"
        :permission-tag-select           "tags"
        :permission-name-text            "permission[name]"
        :permission-description-text     "permission[description]"
        :save-permission                 "save_permission_button"
        :remove-role                     "remove_role"
        :add-permission                  "add_permission"})

(sel/template-fns
 {permission-org                  "//li[@class='slide_link' and starts-with(normalize-space(.),'%s')]"
  plus-icon                       "//li[.='%s']//span[contains(@class,'ui-icon-plus')]"
  role-action                     "//li[.//span[@class='sort_attr' and .='%2$s']]//a[.='%s']"})

(def user-role-toggler (locators/toggler locators/add-remove role-action))

;; Tasks

(defn create
  "Creates a role with the given name and optional description."
  [name & [{:keys [description]}]]
  (nav/go-to :roles-page)
  (sel/browser click :new-role)
  (fill-ajax-form {:new-role-name-text name
                   :new-role-description-text description}
                  :save-role)
  (notification/check-for-success {:match-pred (notification/request-type? :roles-create)}))

(defn assign
  "Assigns the given user to the given roles. Roles should be a list
  of roles to assign."
  [{:keys [user roles]}]
  (nav/go-to :user-roles-permissions-page {:username user})
  (doseq [role roles]
    (sel/browser click (plus-icon role)))
  (sel/browser click :save-roles)
  (notification/check-for-success {:match-pred (notification/request-type? :users-update-roles)}))

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
                     (nav :named-role-permissions-page)
                     (doseq [{:keys [org permissions]} all-perms]
                       (sel/->browser (click (permission-org org))
                                      (sleep 1000))
                       (perms-fn permissions)
                       (sel/browser click :role-permissions))))] ;;go back up to choose next org
    (when users
      (nav :named-role-users-page)
      (doseq [user users]
        (locators/toggle user-role-toggler user true)))
    (each-org remove-permissions
              (fn [permissions]
                (doseq [permission permissions]
                  (sel/browser click (user-role-toggler permission false))
                  (notification/check-for-success {:match-pred (notification/request-type? :roles-destroy-permission)})
                  (sel/browser sleep 5000))))
    (each-org add-permissions
              (fn [permissions]
                (doseq [{:keys [name description resource-type verbs tags]} permissions]
                  (sel/browser click :add-permission)
                  (if (= resource-type :all)
                    (sel/browser click :all-types)
                    (do (sel/browser select :permission-resource-type-select resource-type)
                        (sel/browser click :next)
                        (doseq [verb verbs]
                          (sel/browser addSelection :permission-verb-select verb))
                        (sel/browser click :next)
                        (doseq [tag tags]
                          (sel/browser addSelection :permission-tag-select tag))))
                  (fill-ajax-form {:permission-name-text name
                                   :permission-description-text description}
                                  :save-permission))
                (notification/check-for-success {:match-pred (notification/request-type? :roles-create-permission)})))))

(defn delete
  "Deletes the given role."
  [name]
  (nav/go-to :named-role-page {:role-name name})
  (sel/browser click :remove-role)
  (sel/browser click :confirmation-yes)
  (notification/check-for-success {:match-pred (notification/request-type? :roles-destroy)}))



