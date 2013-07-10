(ns katello.tests.permissions
  (:refer-clojure :exclude [fn])
  (:require [katello :as kt]
            (katello [ui :as ui]
                     [rest :as rest]
                     [navigation :as nav]
                     [notifications :as notification]
                     [organizations :as org]
                     [sync-management :as sync]
                     [content-view-definitions :as views]
                     [changesets :as changeset]
                     [providers :as provider]  ; needs to navigate - no direct dep
                     [ui-common :as common]
                     [login :refer [login logged-in?]]
                     [navigation :as nav]
                     [conf :as conf]
                     [tasks :refer [with-unique uniques uniqueify expecting-error random-ascii-string]]
                     [systems :as system]
                     [client :as client]
                     [users :as user]
                     [roles :as role]
                     [login :as login]
                     [blockers :refer [bz-bugs bz-bug auto-issue]])
            [katello.client.provision :as provision]
            [katello.tests.useful :refer [ensure-exists fresh-repo]]
            [serializable.fn :refer [fn]]
            [test.assert :as assert]
            [test.tree.script :refer [deftest defgroup]]
            [test.tree :refer [blockers]]
            [com.redhat.qe.auto.selenium.selenium :refer [browser ->browser]])
  (:import [com.thoughtworks.selenium SeleniumException]))

;; Functions

(def denied-access? (fn [r] (-> r class (isa? Throwable))))

(def has-access? (complement denied-access?))

(defn- try-all [fs]
  (zipmap fs (doall (for [f fs]
                      (try (f)
                           (catch Throwable e e))))))

(defn- navigate-fn [page & [org]]
  (fn [] (nav/go-to page (or org conf/*session-org*))))

(defn- navigate-all [pages & [org]]
  (for [page pages]
    (navigate-fn page org)))

(defn access-page-via-url [url]
  (assert/is (not (nav/returns-403? url))))

(defn verify-role-access
  [& {:keys [role allowed-actions disallowed-actions]}]
  (with-unique [user (kt/newUser {:name "user-perm"
                                  :password "password"
                                  :email "foo@my.org"})]
    (let [try-all-with-user (fn [actions]
                              (binding [conf/*session-user* user]
                                (login conf/*session-user*)
                                (try-all actions)))]
      (rest/create user)
      (when role
        (ui/update role assoc :users (list user)))
      (try
        (let [with-perm-results (try-all-with-user allowed-actions)
              no-perm-results (try-all-with-user disallowed-actions)]
          (assert/is (and (every? denied-access? (vals no-perm-results))
                          (every? has-access? (vals with-perm-results)))))
        (finally
          (login)))))) ;;as original session user

(defn validate-permissions-navigation
  "Validate Navigation of permissions page under Roles."
  [role {:keys [org resource-type verbs tags name]}]
  (nav/go-to ::role/named-permissions-page role)
  (->browser (click (role/permission-org (:name org)))
             (sleep 1000)
             (click ::role/add-permission)
             (select ::role/permission-resource-type-select resource-type)
             (click ::role/next))
  (doseq [verb verbs]
    (browser addSelection ::role/permission-verb-select verb))
  (browser click ::role/next)
  (doseq [tag tags]
    (browser addSelection ::role/permission-tag-select tag))
  (->browser (click ::role/next)
             (setText ::role/permission-name-text name)
             (setText ::role/permission-description-text "myperm descriptions"))
  (while (browser isVisible ::role/previous)
    (browser click ::role/previous))
  (while (not (browser isVisible ::role/save-permission))
    (browser click ::role/next))
  (browser click ::role/save-permission)
  (notification/success-type :roles-create-permission))

(defn verify-access
  "Assigns a new user to a new role with the given permissions. That
   user is logs in, and tries the allowed-actions to ensure they all
   succeed, finally tries disallowed-actions to make sure they all
   fail. If any setup needs to be done to set up an action, a no-arg
   function can be passed in as setup. (for instance, if you're
   testing a permission to modify users, you need a test user to
   attempt to modify)."
  [& {:keys [permissions allowed-actions disallowed-actions setup]}] {:pre [permissions]}
  (with-unique [role (kt/newRole {:name "permtest"})]
    (when setup (setup))
    (ui/create role)
    (ui/update role assoc :permissions (map kt/newPermission permissions))
    (apply verify-role-access [:role role :allowed-actions allowed-actions :disallowed-actions disallowed-actions])))

(def create-an-env
  (fn [] (-> {:name "blah" :org conf/*session-org*} kt/newEnvironment uniqueify ui/create)))

(def create-an-org
  (fn [] (-> {:name "org"} kt/newOrganization uniqueify ui/create)))

(def create-an-ak ;;FIXME
  (fn [] (ui/create (kt/newActivationKey {:name (uniqueify "blah")
                                          :env (first conf/*environments*)}))))

(def create-a-st ;;FIXME
  (fn [] (ui/create {:name (uniqueify "blah")})))

(def create-a-user
  (fn [] (-> {:name "blah" :password "password" :email "me@me.com"} kt/newUser uniqueify ui/create)))

(def global (kt/newOrganization {:name "Global Permissions"}))

(defn- delete-system-data [sysverb delete-allowed?]
  (fn [] (with-unique [system (kt/newSystem {:env (first conf/*environments*),
                                             :sockets "1",
                                             :system-arch "x86_64",
                                             :facts (system/random-facts)})]
           (let [del-actions (list (fn [] (ui/delete system)))]
             (concat
              [:permissions [{:org global, :name "blah2", :resource-type "Organizations", :verbs [sysverb]}]
               :setup (fn [] (rest/create system))]
              (if delete-allowed?
                [:allowed-actions del-actions]
                [:disallowed-actions del-actions]))))))

(defn- get-cv-pub [org]
  {:cv1 (uniqueify (kt/newContentView {:name "con-def1"
                                       :org org
                                       :published-name "pub-name1"}))
   :cv2 (uniqueify (kt/newContentView {:name "con-def2"
                                       :org org
                                       :published-name "pub-name2"}))
   :cv3 (uniqueify (kt/newContentView {:name "con-def3"
                                       :org org
                                       :published-name "pub-name3"}))
   :env (uniqueify (kt/newEnvironment {:name  "dev"
                                       :org org}))})

(defn- setup-cv-publish [org env cv1 cv2 cv3]
  (ui/create-all (list org env cv1 cv2))
  (views/publish {:content-defn cv1 :published-name (cv1 :published-name) :description "pub-name1 desc"})
  (views/publish {:content-defn cv2 :published-name (cv2 :published-name) :description "pub-name2 desc"})
  (views/publish {:content-defn cv2 :published-name (cv3 :published-name) :description "pub-name3 desc"}))


(def access-test-data
  (let [baseuser (kt/newUser {:name "user" :password "password" :email "me@me.com"})
        baseorg (kt/newOrganization {:name "org"})]
    [(fn [] [:permissions [{:org global, :resource-type "Organizations", :verbs ["Read Organization"], :name "orgaccess"}]
             :allowed-actions [(fn [] (nav/go-to conf/*session-org*))]
             :disallowed-actions (conj (navigate-all [:katello.systems/page :katello.sync-management/status-page
                                                      :katello.providers/custom-page
                                                      :katello.changesets/page])
                                       (fn [] (ui/create (uniqueify baseorg)))
                                       create-an-env)])

     (fn [] (with-unique [org (kt/newOrganization {:name "org-create-perm"})
                          prov (kt/newProvider {:name "myprov" :org org})]
              [:permissions [{:org global, :resource-type "Organizations", :verbs ["Administer Organization"], :name "orgcreate"}]
               :allowed-actions [(fn [] (ui/create org)) (fn [] (ui/delete org)) create-an-env]
               :disallowed-actions (conj (navigate-all [:katello.systems/page :katello.sync-management/status-page
                                                        :katello.providers/custom-page
                                                        :katello.changesets/page]
                                                       org )
                                         (fn [] (ui/create prov))
                                         (fn [] (rest/create prov)))]))

     (vary-meta
      (fn [] [:permissions [{:org global, :resource-type "Environments",
                             :verbs ["Register Systems in Environment"
                                     "Read Environment Contents"], :name "systemreg"}
                            {:org global, :resource-type "Organizations",
                             :verbs ["Read Organization"], :name "readorg"}]
              :allowed-actions [(fn [] (-> {:name "system"
                                            :env (first conf/*environments*)
                                            :facts (system/random-facts)} kt/newSystem uniqueify rest/create))
                                (navigate-fn :katello.systems/page)]
              :disallowed-actions (conj (navigate-all [:katello.providers/custom-page])
                                        create-an-org)])
      assoc :blockers (bz-bugs "757775"))

     (vary-meta
      (fn [] [:permissions [{:org global, :resource-type "Activation Keys", :verbs ["Read Activation Keys"], :name "akaccess"}]
              :allowed-actions [(navigate-fn :katello.activation-keys/page)]
              :disallowed-actions (conj (navigate-all [:katello.organizations/page
                                                       :katello.systems/page :katello.systems/by-environments-page
                                                       :katello.repositories/redhat-page])
                                        create-an-ak)])
      assoc :blockers (bz-bugs "757817"))

     (vary-meta
      (fn [] [:permissions [{:org global, :resource-type "Activation Keys", :verbs ["Administer Activation Keys"], :name "akmang"}]
              :allowed-actions [create-an-ak]
              :disallowed-actions (conj (navigate-all [:katello.organizations/page
                                                       :katello.systems/page :katello.systems/by-environments-page
                                                       :katello.repositories/redhat-page])
                                        create-an-org)])
      assoc :blockers (bz-bugs "757817"))

     (vary-meta
      (fn [] [:permissions [{:org global, :resource-type "Users", :verbs ["Read Users"], :name "userread"}]
              :allowed-actions [(navigate-fn :katello.users/page)]
              :disallowed-actions (conj (navigate-all [:katello.systems/page :katello.organizations/page :katello.roles/page
                                                       :katello.changesets/page])
                                        create-an-org
                                        create-an-env
                                        create-a-user)])
      assoc :blockers (bz-bugs "953606"))

     (vary-meta
      (fn [] (with-unique [user baseuser]
               [:setup (fn [] (rest/create user))
                :permissions [{:org global, :resource-type "Users", :verbs ["Modify Users"], :name "usermod"}]
                :allowed-actions [(fn [] (ui/update user assoc :email "blah@me.com"))]
                :disallowed-actions (conj (navigate-all [:katello.systems/page :katello.organizations/page :katello.roles/page
                                                         :katello.changesets/page])
                                          (fn [] (with-unique [cannot-delete baseuser]
                                                   (ui/create cannot-delete)
                                                   (ui/delete cannot-delete))))]))
      assoc :blockers (bz-bugs "953606"))

     (vary-meta
      (fn [] (with-unique [user baseuser]
               [:permissions [{:org global, :resource-type "Users", :verbs ["Delete Users"], :name "userdel"}]
                :setup (fn [] (rest/create user))
                :allowed-actions [(fn [] (ui/delete user))]
                :disallowed-actions (conj (navigate-all [:katello.systems/page :katello.organizations/page :katello.roles/page
                                                         :katello.changesets/page])
                                          create-a-user)]))
      assoc :blockers (bz-bugs "953606"))

     (fn [] (with-unique [org baseorg]
              [:permissions [{:org conf/*session-org*, :resource-type "Organizations", :verbs ["Read Organization"], :name "orgaccess"}]
               :setup (fn [] (rest/create org))
               :allowed-actions [(fn [] (nav/go-to conf/*session-org*))]
               :disallowed-actions (conj (navigate-all [:katello.systems/page :katello.sync-management/status-page
                                                        :katello.providers/custom-page
                                                        :katello.changesets/page])
                                         (fn [] (nav/go-to org)))]))

     (vary-meta
      (fn [] (with-unique [org      baseorg
                           pub-name (uniqueify "pub1")
                           cv       (kt/newContentView {:name "con-def"
                                                        :org conf/*session-org*})]
               [:permissions [{:org global, :resource-type "Content View Definitions", :verbs ["Read Content View Definitions" "Administer Content View Definitions"], :name "cvaccess_create"}]
                :allowed-actions [(navigate-fn :katello.content-view-definitions/page)
                                  (fn [] (ui/create cv))
                                  (fn [] (views/clone cv (update-in cv [:name] #(str % "-clone"))))]
                :disallowed-actions (conj (navigate-all [:katello.systems/page :katello.sync-management/status-page
                                                         :katello.providers/custom-page
                                                         :katello.changesets/page])
                                          (fn [] (views/publish {:content-defn cv :published-name pub-name :description "pub name desc"})))]))
      assoc :blockers (auto-issue "800"))

     (fn [] (with-unique [org      baseorg
                          cv       (kt/newContentView {:name "con-def"
                                                       :org conf/*session-org*})
                          cv1       (kt/newContentView {:name "con-def1"
                                                        :org conf/*session-org*})]
              [:permissions [{:org global, :resource-type "Content View Definitions", :verbs ["Read Content View Definitions"], :name "cvaccess_read"}]
               :setup (fn [] (ui/create cv))
               :allowed-actions [(fn [] (nav/go-to cv))]
               :disallowed-actions (conj (navigate-all [:katello.systems/page :katello.sync-management/status-page
                                                        :katello.providers/custom-page
                                                        :katello.changesets/page])
                                         (fn [] (ui/create cv1))
                                         (fn [] (views/clone cv (update-in cv [:name] #(str % "-clone"))))
                                         (fn [] (ui/update cv assoc :description "cvaccess_read desc"))
                                         (fn [] (views/publish {:content-defn cv :published-name "pub1" :description "pub name desc"}))
                                         (fn [] (ui/delete cv)))]))

     (fn [] (with-unique [org      baseorg
                          cv       (kt/newContentView {:name "con-def"
                                                       :org conf/*session-org*})
                          cv1       (kt/newContentView {:name "con-def1"
                                                        :org conf/*session-org*})]
              [:permissions [{:org global, :resource-type "Content View Definitions", :verbs ["Read Content View Definitions" "Modify Content View Definitions" "Publish Content View Definitions" ], :name "cvaccess_publish"}]
               :setup (fn [] (ui/create cv))
               :allowed-actions [(fn [] (ui/update cv assoc :description "cvaccess_publish desc"))
                                 (fn [] (views/publish {:content-defn cv :published-name "pub1" :description "pub name desc"}))]
               :disallowed-actions (conj (navigate-all [:katello.systems/page :katello.sync-management/status-page
                                                        :katello.providers/custom-page
                                                        :katello.changesets/page])
                                         (fn [] (ui/create cv1))
                                         (fn [] (ui/delete cv)))]))

     (fn [] (with-unique [org      baseorg
                          cv       (kt/newContentView {:name "con-def"
                                                       :org conf/*session-org*})
                          cv1       (kt/newContentView {:name "con-def1"
                                                        :org conf/*session-org*})]
              [:permissions [{:org global, :resource-type "Content View Definitions", :verbs ["Read Content View Definitions" "Modify Content View Definitions"], :name "cvaccess_modify"}]
               :setup (fn [] (ui/create cv))
               :allowed-actions [(fn [] (ui/update cv assoc :description "cvaccess_modify desc"))]
               :disallowed-actions (conj (navigate-all [:katello.systems/page :katello.sync-management/status-page
                                                        :katello.providers/custom-page
                                                        :katello.changesets/page])
                                         (fn [] (ui/create cv1))
                                         (fn [] (views/publish {:content-defn cv :published-name "pub1" :description "pub name desc"}))
                                         (fn [] (ui/delete cv)))]))

     (vary-meta
       (fn [] (with-unique [org      baseorg
                            cv       (kt/newContentView {:name "con-def"
                                                         :org conf/*session-org*})
                            cv1       (kt/newContentView {:name "con-def1"
                                                          :org conf/*session-org*})]
                [:permissions [{:org global, :resource-type "Content View Definitions", :verbs ["Read Content View Definitions" "Delete Content View Definitions"], :name "cvaccess_delete"}]
                 :setup (fn [] (ui/create cv))
                 :allowed-actions [(fn [] (ui/delete cv))]
                 :disallowed-actions (conj (navigate-all [:katello.systems/page :katello.sync-management/status-page
                                                          :katello.providers/custom-page
                                                          :katello.changesets/page])
                                           (fn [] (ui/create cv1))
                                           (fn [] (ui/update cv assoc :description "cvaccess_delete desc"))
                                           (fn [] (views/publish {:content-defn cv :published-name "pub1" :description "pub name desc"})))]))
       assoc :blockers (bz-bugs "977823"))
       
     (vary-meta
      (fn [] (let [org (uniqueify baseorg)
                   {:keys [cv1 cv2 cv3 env]}  (get-cv-pub org)
                   cs (uniqueify (kt/newChangeset {:name "cs"
                                                   :env env
                                                   :content (list cv1 cv2 cv3)}))]
               [:permissions [{:org org, :resource-type "Content View Definitions", :name "cvaccess_cvdefs"}
                              {:org org, :resource-type "Content View", :verbs ["Promote Content Views"], :tags [(cv1 :published-name) (cv3 :published-name)], :name "cvaccess_cvviews"}
                              {:org org, :resource-type "Environments", :verbs ["Read Environment Contents" "Read Changesets in Environment" "Administer Changesets in Environment" "Promote Content to Environment"], :name "cvaccess_cvenvs"}]
                :setup (fn [] (setup-cv-publish org env cv1 cv2 cv3))
                :allowed-actions [(navigate-fn :katello.changesets/page)]
                :disallowed-actions (conj (navigate-all [:katello.systems/page :katello.sync-management/status-page
                                                         :katello.providers/custom-page])
                                          (fn [] (changeset/promote-delete-content cs)))]))
      assoc :blockers (conj (bz-bugs "960620") (auto-issue "800")))

     (fn [] (let [org (uniqueify baseorg)
                  {:keys [cv1 cv2 cv3 env]}  (get-cv-pub org)
                  cs  (uniqueify (kt/newChangeset {:name "cs"
                                                   :env env
                                                   :content (list cv1 cv3)}))]
              [:permissions [{:org org, :resource-type "Content View Definitions", :name "cvaccess_cvdefs"}
                             {:org org, :resource-type "Content View", :verbs ["Read Content Views" "Promote Content Views"], :tags [(cv1 :published-name) (cv3 :published-name)], :name "cvaccess_cvviews"}
                             {:org org, :resource-type "Environments", :verbs ["Read Environment Contents" "Read Changesets in Environment" "Administer Changesets in Environment" "Promote Content to Environment"], :name "cvaccess_cvenvs"}]
               :setup (fn [] (setup-cv-publish org env cv1 cv2 cv3))
               :allowed-actions [(fn [] (changeset/promote-delete-content cs))]
               :disallowed-actions [(navigate-all [:katello.systems/page :katello.sync-management/status-page
                                                   :katello.providers/custom-page])]]))
     
     (fn [] (with-unique [org baseorg]
              [:permissions [{:org org, :resource-type :all, :name "orgaccess"}]
               :setup (fn [] (ui/create org))
               :allowed-actions [(fn [] (navigate-all [:katello.systems/page :katello.sync-management/status-page
                                                       :katello.providers/custom-page
                                                       :katello.changesets/page]))]
               :disallowed-actions [(fn [] (org/switch))]]))
     
     (vary-meta
      (fn [] (with-unique [org (kt/newOrganization {:name "cv-org"})
                           env (kt/newEnvironment {:name  "dev"
                                                   :org org})
                           cv (kt/newContentView {:name "con-def3"
                                                  :org org
                                                  :published-name "pub-name3"})
                           cs (kt/newChangeset {:name "cs"
                                                :env env
                                                :content (list cv)})
                           ak (kt/newActivationKey {:name "ak"
                                                    :env env
                                                    :description "auto activation key"
                                                    :content-view cv})]
               (let [repo (fresh-repo org "http://inecas.fedorapeople.org/fakerepos/cds/content/safari/1.0/x86_64/rpms/")
                     prd   (kt/product repo)
                     prv   (kt/provider repo)]
                 [:permissions [{:org org, :resource-type "Content View Definitions", :name "cvaccess_cvdefs"}
                                {:org org, :resource-type "Content View", :name "cvaccess_cvviews"}
                                {:org org, :resource-type "Environments", :name "cvaccess_cvenvs",
                                 :verbs ["Read Environment Contents" "Read Changesets in Environment" "Administer Changesets in Environment" "Promote Content to Environment" "Modify Systems in Environment" "Read Systems in Environment" "Register Systems in Environment"]}
                                {:org org, :resource-type "Activation Keys", :name "cvaccess_ak"}]
                  :setup (fn [] (ui/create-all (list org env prv prd repo cv))
                           (sync/perform-sync (list repo))
                           (ui/update cv assoc :products (list (kt/product repo)))
                           (views/publish {:content-defn cv
                                           :published-name (cv :published-name)
                                           :description "test pub"
                                           :org org}))
                  :allowed-actions [(fn [] (changeset/promote-delete-content cs)
                                      (ui/create ak)
                                      (ui/update ak assoc :subscriptions (list (:name prd)))
                                      (provision/with-queued-client
                                        ssh-conn
                                        (client/register ssh-conn
                                                         {:org (:name org)
                                                          :activationkey (:name ak)})
                                        (client/sm-cmd ssh-conn :refresh)
                                        (client/run-cmd ssh-conn "yum repolist")
                                        (let [cmd1 (format "yum install -y crow")
                                              cmd2 (format "rpm -qav crow")
                                              result1 (client/run-cmd ssh-conn cmd1)
                                              result2 (client/run-cmd ssh-conn cmd2)]
                                          (assert/is (->> result1 :exit-code (= 0)))
                                          (assert/is (->> result2 :exit-code (= 0))))))]
                  :disallowed-actions [(navigate-all [:katello.sync-management/status-page
                                                      :katello.providers/custom-page])]])))
      assoc :blockers (bz-bugs "970570"))
     
     (fn [] (with-unique [org baseorg
                          env (kt/newEnvironment {:name "blah" :org org})]
              [:permissions [{:org org, :resource-type :all, :name "orgadmin"}]
               :setup (fn [] (rest/create org))
               :allowed-actions (conj (navigate-all [:katello.systems/page :katello.sync-management/status-page
                                                     :katello.providers/custom-page
                                                     :katello.changesets/page]
                                                    org)
                                      (fn [] (nav/go-to org))
                                      (fn [] (ui/create env)))
               :disallowed-actions [(fn [] (nav/go-to conf/*session-org*))]]))
     
     (fn [] (with-unique [org baseorg
                          env (kt/newEnvironment {:name "blah" :org org})
                          user (kt/newUser {:name "role-user" :password "abcd1234" :email "me@my.org"})]
              [:permissions [{:org org, :resource-type :all, :name "fullaccess"}]
               :setup (fn [] (rest/create org)
                        (ui/create user))
               :allowed-actions [(fn [] (browser mouseOver ::user/user-account-dropdown)
                                   (browser click ::user/account)
                                   (nav/browser-fn (click ::user/roles-link)))]
               :disallowed-actions [(fn [] (browser mouseOver ::user/user-account-dropdown)
                                      (browser click ::user/account)
                                      (nav/browser-fn (click ::user/roles-link))
                                      (browser click ::user/add-role))]]))

     (fn [] (let [nav-fn (fn [uri] (fn [] (->> uri (str "/katello/") access-page-via-url)))]
              [:permissions []
               :allowed-actions (map nav-fn ["users"])
               :disallowed-actions (map nav-fn ["subscriptions" "systems" "systems/environments" "system_groups"
                                                "roles" "sync_management/index" "content_search" "organizations" "providers"])]))

     (delete-system-data "Read Systems" false)
     (delete-system-data "Delete Systems" true)]))


;; Tests
(defn- create-role* [f name]
  (-> {:name name} kt/newRole f ui/create))

(def create-unique-role (partial create-role* uniqueify))
(def create-role (partial create-role* identity))

(defgroup permission-tests

  (deftest "Create a role"
    :uuid "25b56d86-5d5a-ff34-7a63-ceb1608b4881"
    (create-unique-role "testrole"))

  (deftest "Create a role with i18n characters"
    :uuid "b53d9ae6-6d9c-f454-2b63-4e7edd78ebeb"
    :data-driven true

    create-unique-role
    [["صالح"] ["Гесер"] ["洪"]["標準語"]])

  (deftest "Role validation"
    :uuid "599d0416-a8aa-1264-c96b-67e5136e45b2"
    :data-driven true

    (fn [rolename expected-err]
      (expecting-error (common/errtype expected-err)
                       (create-role rolename)))

    [[(random-ascii-string 149)  :katello.notifications/name-too-long]
     ["  foo" :katello.notifications/name-no-leading-trailing-whitespace]
     ["  foo   " :katello.notifications/name-no-leading-trailing-whitespace]
     ["foo " :katello.notifications/name-no-leading-trailing-whitespace]
     ["" :katello.notifications/name-cant-be-blank]
     (with-meta ["<a href='http://malicious.url/'>Click Here</a>" :katello.notifications/katello-error]
       {:blockers (bz-bugs "901657")}) ; TODO create more specific error after fix
     ])

  (deftest "Remove a role"
    :uuid "4676d07e-1b61-ae44-af9b-1dd01027bd37"
    (doto (uniqueify (kt/newRole {:name "deleteme-role"}))
      (ui/create)
      (ui/delete)))

  (deftest "Verify the Navigation of Roles, related to permissions"
    :uuid "db588bc0-6f95-4534-7673-e612cd00d8f8"
    :data-driven true

    (fn [{:keys [resource-type verbs tags setup] :as perm} & [setup]]
      (when setup (setup))
      (with-unique [role  (kt/newRole {:name "myrole"})
                    perm  (-> perm
                              (assoc :org conf/*session-org*, :name "perm")
                              kt/newPermission)]
        (ui/create role)
        (assert/is (validate-permissions-navigation role perm))))

    [(fn [] (let [env (kt/newEnvironment {:name "Dev" :org conf/*session-org*})]
              [{:resource-type "Environments",
                :verbs #{"Administer Changesets in Environment"},
                :tags #{(:name env)}}
               #(ensure-exists env)]))])

  (deftest "Add a permission and user to a role"
    :uuid "14ecb28a-92fa-fc74-0ff3-ea142f08171c"
    (with-unique [user (kt/newUser {:name "role-user" :password "abcd1234" :email "me@my.org"})
                  role (kt/newRole {:name "edit-role"})]
      (ui/create-all (list user role))
      (ui/update role assoc
                 :permissions [{:org global
                                :name "blah2"
                                :resource-type "Organizations"
                                :verbs ["Read Organization"]}]
                 :users [user]))

    (deftest "Verify user with specific permission has access only to what permission allows"
      :uuid "6ea63d4f-49f1-8244-564b-79a265bebc2c"
      :data-driven true
     
      verify-access
      access-test-data)))
