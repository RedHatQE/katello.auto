(ns katello.roles
  (:require [com.redhat.qe.auto.selenium.selenium :refer [browser ->browser]]
            (katello [locators :as locators] 
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

;; Tasks

(defn create
  "Creates a role with the given name and optional description."
  [name & [{:keys [description]}]]
  (navigate :roles-page)
  (browser click :new-role)
  (fill-ajax-form {:new-role-name-text name
                   :new-role-description-text description}
                  :save-role)
  (notification/check-for-success {:match-pred (notification/request-type? :roles-create)}))

(defn assign
  "Assigns the given user to the given roles. Roles should be a list
  of roles to assign."
  [{:keys [user roles]}]
  (navigate :user-roles-permissions-page {:username user})
  (doseq [role roles]
    (browser click (locators/plus-icon role)))
  (browser click :save-roles)
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
  (let [nav (fn [page] (navigate page {:role-name name}))
        each-org (fn [all-perms perms-fn]
                   (when all-perms
                     (nav :named-role-permissions-page)
                     (doseq [{:keys [org permissions]} all-perms]
                       (->browser (click (locators/permission-org org))
                                  (sleep 1000))
                       (perms-fn permissions)
                       (browser click :role-permissions))))] ;;go back up to choose next org
    (when users
      (nav :named-role-users-page)
      (doseq [user users]
        (locators/toggle locators/user-role-toggler user true)))
    (each-org remove-permissions
              (fn [permissions]
                (doseq [permission permissions]
                  (browser click (locators/user-role-toggler permission false))
                  (notification/check-for-success {:match-pred (notification/request-type? :roles-destroy-permission)})
                  (browser sleep 5000))))
    (each-org add-permissions
              (fn [permissions]
                (doseq [{:keys [name description resource-type verbs tags]} permissions]
                  (browser click :add-permission)
                  (if (= resource-type :all)
                    (browser click :all-types)
                    (do (browser select :permission-resource-type-select resource-type)
                        (browser click :next)
                        (doseq [verb verbs]
                          (browser addSelection :permission-verb-select verb))
                        (browser click :next)
                        (doseq [tag tags]
                          (browser addSelection :permission-tag-select tag))))
                  (fill-ajax-form {:permission-name-text name
                                   :permission-description-text description}
                                  :save-permission))
                (notification/check-for-success {:match-pred (notification/request-type? :roles-create-permission)})))))

(defn delete
  "Deletes the given role."
  [name]
  (navigate :named-role-page {:role-name name})
  (browser click :remove-role)
  (browser click :confirmation-yes)
  (notification/check-for-success {:match-pred (notification/request-type? :roles-destroy)}))



