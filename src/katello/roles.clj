(ns katello.roles
  (:require [com.redhat.qe.auto.selenium.selenium :refer [browser ->browser]]
            (katello [locators :as locators] 
                     [notifications :refer [check-for-success]] 
                     [ui-tasks :refer [fill-ajax-form navigate]]))
    )

;;
;; Roles
;;

(defn create
  "Creates a role with the given name and optional description."
  [name & [{:keys [description]}]]
  (navigate :roles-page)
  (browser click :new-role)
  (fill-ajax-form {:new-role-name-text name
                   :new-role-description-text description}
                  :save-role)
  (check-for-success))

(defn assign
  "Assigns the given user to the given roles. Roles should be a list
  of roles to assign."
  [{:keys [user roles]}]
  (navigate :user-roles-permissions-page {:username user})
  (doseq [role roles]
    (browser click (locators/plus-icon role)))
  (browser click :save-roles)
  (check-for-success))

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
                  (check-for-success)
                  (browser sleep 5000))))
    (each-org add-permissions
              (fn [permissions]
                (doseq [{:keys [name description resource-type verbs tags]} permissions]
                  (browser click :add-permission)
                  (browser select :permission-resource-type-select resource-type)
                  (browser click :next)
                  (doseq [verb verbs]
                    (browser addSelection :permission-verb-select verb))
                  (browser click :next)
                  (doseq [tag tags]
                    (browser addSelection :permission-tag-select tag))
                  (fill-ajax-form {:permission-name-text name
                                   :permission-description-text description}
                                  :save-permission))
                (check-for-success)))))

(defn delete
  "Deletes the given role."
  [name]
  (navigate :named-role-page {:role-name name})
  (browser click :remove-role)
  (browser click :confirmation-yes)
  (check-for-success))



