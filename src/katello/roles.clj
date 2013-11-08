(ns katello.roles
  (:require [webdriver :as browser]
            [clojure.data :as data]
            katello
            (katello [navigation :as nav]
                     [notifications :as notification]
                     [tasks :as tasks]
                     [content-search :as cs]
                     [ui :as ui]
                     [rest :as rest]
                     [ui-common :as common])))

;; Locators

(ui/defelements :katello.deployment/any [katello.ui]
  {::new                             "//a[@id='new']"
   ::name-text                       {:tag :input, :name "role[name]"}
   ::description-text                {:tag :textarea, :name "role[description]"}
   ::save                            "role_save"
   ::users                           "role_users"
   ::permissions                     "role_permissions"
   ::next                            "next_button"
   ::previous                        "previous_button"
   ::permission-resource-type-select {:name "permission[resource_type_attributes[name]]"}
   ::permission-verb-select          {:name "permission[verb_values][]"}
   ::permission-tag-select           {:name "permission[tag_values][]"}
   ::permission-name-text            {:tag :input, :name "permission[name]"}
   ::permission-description-text     {:tag :textarea, :name "permission[description]"}
   ::save-permission                 "save_permission_button"
   ::remove                          "remove_role"
   ::add-permission                  "add_permission"
   ::all-types                       "all_types"
   ::slide-link-home                 "//span[@id='roles']"
   ::all-verbs                       "all_verbs"
   ::role-opt-list                    "//div[@id='roles_tree']//ul[@class='filterable']//li"
   ::all-tags                        "all_tags"}
  )

(browser/template-fns
 {permission-org "//li[@class='slide_link' and starts-with(normalize-space(.),'%s')]"
  permission-perm "//li[@class='slide_link' and contains(.,'%s')]"
  role-action    "//li[.//span[@class='sort_attr' and .='%2$s']]//a[.='%s']"})

;; Nav

(nav/defpages :katello.deployment/any katello.menu
  [::page
   [::named-page (fn [role] (nav/choose-left-pane role))
    [::named-users-page (fn [] (browser/click ::users))]
    [::named-permissions-page (fn [] (browser/click ::permissions))]]])


;; Vars

(def administrator (katello/newRole {:name "Administrator"}))

;; Tasks

(def user-role-toggler (ui/toggler ui/add-remove role-action))

(defn- create
  "Creates a role with the given name and optional description."
  [{:keys [name description]}]
  (nav/go-to ::page)
  (browser/click ::new)
  (browser/quick-fill [::name-text name
                       ::description-text description
                       ::save browser/click])
  (notification/success-type :roles-create))

(defn- goto-org-perms [org]
  (browser/click ::permissions)
  (browser/click (permission-org (:name org)))
  (Thread/sleep 1000))

(defn- add-permissions [permissions]
  (browser/click ::slide-link-home)
  (doseq [{:keys [name org description resource-type verbs tags]} permissions]
    (goto-org-perms org)
    (browser/click ::add-permission)
    (if (= resource-type :all)
      (browser/click ::all-types)
      (do (browser/select-by-text ::permission-resource-type-select resource-type)
          (browser/click ::next)
          (cond
           (and (not verbs) (browser/visible? ::all-verbs))
           (browser/click ::all-verbs)

           (not (nil? verbs))
           (doseq [verb verbs]
             (browser/select-by-text  ::permission-verb-select verb)))
          (browser/click ::next)
          (cond
           (and (not tags) (browser/visible? ::all-tags))
           (browser/click ::all-tags)

           (not (nil? tags))
           (doseq [tag tags]
             (browser/select-by-text  ::permission-tag-select tag)))))
    (browser/quick-fill [::permission-name-text name
                         ::permission-description-text description
                         ::save-permission browser/click])
    (notification/success-type :roles-create-permission)))


(defn- remove-permissions [permissions]
  (browser/click ::slide-link-home)
  (doseq [{:keys [name org]} permissions]
    (goto-org-perms org)
    (browser/click (user-role-toggler name false))
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
      (browser/click ::users)
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
  (browser/click ::remove)
  (browser/click ::ui/confirmation-yes)
  (notification/success-type :roles-destroy))

(defn read-it 
  "Tries to read a role based on the name"
  [role]
  (let [get-list   #(do (Thread/sleep 1000)
                        (second (cs/get-search-page-result-list-of-lists-xml "roles_list")) )
        every-second #(take-nth 2 (rest %))
        user-names (do 
                     (nav/go-to role) 
                     (browser/click ::users)
                     (->> 
                       (get-list)
                       (every-second)
                       doall)) 
        orgs (do
                        (nav/go-to role) 
                        (browser/click ::permissions)
                        (->> 
                            (get-list)
                            (map #(clojure.string/split % #"( \()|(\))"))
                            (map (fn [[a b]] [a (Integer/parseInt b)]))
                            (filter (fn [[a b]] (> b 0)))
                            (map first)
                             doall)) 
        org-perm (doall (for [org orgs]
                               (do
                                 (nav/go-to role) 
                                 (browser/click ::permissions)
                                 (browser/click (permission-org org))
                                 [org (doall (every-second (get-list)))])))

       perms 
         (doall (for [[org plist] org-perm]  
                        (for [p plist]
                          (do
                            (nav/go-to role) 
                            (browser/click ::permissions)
                            (browser/click (permission-org org))
                            (browser/click (permission-perm p))
                            (->>
                              (get-list)
                              (filter coll?)
                              (map second)
                              (zipmap [:name :resource-type :verbs :on])
                              (#(if (coll? (:verbs %)) %
                                  (assoc % :verbs [(% :verbs)])))
                              (merge {:org (katello/newOrganization {:name org})})
                            )))))]

    (katello/newRole {:name (:name role) :users user-names :permissions perms})))


(extend katello.Role
  ui/CRUD {:create create
           :update* edit
           :read read-it
           :delete delete}

  rest/CRUD (let [url "api/roles"
                  url-by-id (partial rest/url-maker [["api/roles/%s" [identity]]])]
              {:id rest/id-field})

  tasks/Uniqueable tasks/entity-uniqueable-impl
  nav/Destination {:go-to (partial nav/go-to ::named-page)})

(extend katello.Permission
  tasks/Uniqueable tasks/entity-uniqueable-impl)
