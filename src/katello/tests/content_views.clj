(ns katello.tests.content-views
  (:require (katello [conf :refer [*session-org*] :as conf]
                     [ui :as ui]
                     [rest :as rest]
                     [client :as client]
                     [content-view-definitions :as views]
                     [notifications :as notifications]
                     [organizations :as organization]
                     [providers :as provider]
                     [repositories :as repo]
                     [sync-management :as sync]
                     [changesets :as changeset]
                     [tasks :refer :all]
                     [ui-common :as common]
                     [validation :refer :all])
            [test.tree.script :refer :all]
            [katello :as kt]
            [katello.client.provision :as provision]
            [test.assert :as assert]
            [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            [katello.tests.useful :refer [fresh-repo create-recursive]]
            [katello.tests.organizations :refer [setup-custom-org-with-content]]
            [katello :refer [newOrganization newProvider newProduct newRepository newContentView]]
            [bugzilla.checker :refer [open-bz-bugs]]))

;; Functions
(defn promote-published-content-view
  "Function to promote published content view"
  [org target-env repo]
  (with-unique [pub-name "publish-name"
                cv (kt/newContentView {:name "content-view"
                         :org org
                         :published-name pub-name})
                cs (kt/newChangeset {:name "cs"
                                     :env target-env
                                     :content (list cv)})]
    (ui/create-all (list org target-env cv))
    (create-recursive repo)
    (sync/perform-sync (list repo))
    (ui/update cv assoc :products (list (kt/product repo)))
    (views/publish {:content-defn cv
                    :published-name pub-name
                    :description "test pub"
                    :org org})
    (changeset/promote-delete-content cs)
    cv))

;; Tests

(let [success #(-> % :type (= :success))
      pulp-repo "http://repos.fedorapeople.org/repos/pulp/pulp/v2/stable/6Server/"
      zoo-repo "http://inecas.fedorapeople.org/fakerepos/zoo/"]

  (defgroup content-views-tests

    (deftest "Create a new content view definition"
      (-> {:name "view-def" :org *session-org*}
          katello/newContentView
          uniqueify
          ui/create))

    (deftest "Create a new content view definition w/ i18n characters"
      :blockers (open-bz-bugs "953594")
      :data-driven true

      (fn [view-name]
        (-> {:name view-name :org *session-org*}
            katello/newContentView
            uniqueify
            ui/create))
      i8n-chars)

    (deftest "Create a new content view with a blank name"
      :tcms "248517"

      (expecting-error (common/errtype ::notifications/name-cant-be-blank)
                       (-> {:name "" :org *session-org*}
                           katello/newContentView
                           ui/create)))

    (deftest "Create a new content view with a long name"
      :data-driven true
      :tcms "248518"

      (fn [view-name expected-res]
        (let [content-view (katello/newContentView {:name view-name :org *session-org*})]
          (expecting-error expected-res (ui/create content-view))))

      [[(random-string (int \a) (int \z) 128) (common/errtype ::notifications/name-128-char-limit)]
       [(random-string (int \a) (int \z) 127) success]])

    (deftest "Create a new content view definition using the same name"
      (with-unique [content-def (kt/newContentView {:name "con-def"
                                                    :org conf/*session-org*})]
        (ui/create content-def)
        (expecting-error (common/errtype ::notifications/name-taken-error)
                         (ui/create content-def))))

    (deftest "Delete a content view definition"
      (doto (-> {:name "view-def" :org *session-org*}
                kt/newContentView
                uniqueify)
        (ui/create)
        (ui/delete)))

    (deftest "Clone empty content view definition"
      (with-unique [content-def (kt/newContentView {:name "con-def"
                                                    :org conf/*session-org*})]
        (ui/create content-def)
        (views/clone content-def (update-in content-def [:name] #(str % "-clone"))))

      (deftest "Clone content view definition with content"
        :tcms "248743"
        
        (with-unique [content-def (kt/newContentView {:name "con-def"
                                                      :org conf/*session-org*})
                      repo (fresh-repo (kt/org content-def) pulp-repo)]
          (ui/create content-def)
          (create-recursive repo)
          (ui/update content-def assoc :products (list (kt/product repo)))
          (views/clone content-def (update-in content-def [:name] #(str % "-clone"))))))


    (deftest "Publish content view definition"
      (with-unique [content-def (kt/newContentView {:name "con-def"
                                                    :org conf/*session-org*})]
        (ui/create content-def)
        (views/publish {:content-defn content-def
                        :published-name (uniqueify "pub-name")
                        :org *session-org*})))

    (deftest "Published content view name links to content search page"
      (with-unique [content-def (kt/newContentView {:name "con-def"
                                                    :org conf/*session-org*})
                    pub-name "pub-view"]
        (ui/create content-def)
        (views/publish {:content-defn content-def 
                        :published-name pub-name 
                        :org *session-org*})
        (let [{:strs [href]} (browser getAttributes (views/publish-view-name pub-name))]
          (assert (and (.startsWith href "/katello/content_search")
                       (.contains href pub-name))))))
    
    (deftest "Create a new content-view/composite definition and add a product"
      :data-driven true

      (fn [composite?]
        (with-unique [org (newOrganization {:name "auto-org"})
                      content-view (kt/newContentView {:name "auto-view-definition"
                                                       :org org})
                      repo (fresh-repo org pulp-repo)
                      published-name "pub-name"
                      composite-view (kt/newContentView {:name "composite-view"
                                                         :org org
                                                         :description "Composite Content View"
                                                         :composite 'yes'
                                                         :composite-name published-name})]
          (ui/create-all (list org content-view))
          (create-recursive repo)
          (ui/update content-view assoc :products (list (kt/product repo)))
          (views/publish {:content-defn content-view
                          :published-name published-name
                          :org *session-org*})
          (when composite?
            (ui/create composite-view))))

      [[true]
       [false]])

    (deftest "Edit a content view definition"
      (with-unique [org (kt/newOrganization {:name "auto-org"})
                    content-definition (kt/newContentView {:name "auto-view-definition"
                                                           :description "new description"
                                                           :org org})
                    modified-name "mod-name"]
        (ui/create-all (list org content-definition))
        (ui/update content-definition assoc :name modified-name :description "modified description")))


    (deftest "Remove complete product or a repo from content-view-defnition"
      (with-unique [org (kt/newOrganization {:name "auto-org"})
                    content-defn (kt/newContentView {:name "auto-view-definition"
                                                     :org org})
                    repo (fresh-repo org pulp-repo)]
        (ui/create-all (list org content-defn))
        (create-recursive repo)
        (-> content-defn (ui/update assoc :products (list (kt/product repo)))
            (ui/update dissoc :products))))


    (deftest "Create composite content-definition with two products"
      (with-unique [org (kt/newOrganization {:name "auto-org"})]
        (let [repo1 (fresh-repo org pulp-repo)
              repo2 (fresh-repo org zoo-repo)
              published-names (take 2 (uniques "publish-name"))
              content-defns (->> {:name "view-definition"
                                  :org org} kt/newContentView uniques (take 2))]
          (ui/create org)
          (ui/create-all content-defns)
          (doseq [repo [repo1 repo2]]
            (create-recursive repo))
          (doseq [[repo content-defns published-names] [[repo1  (first content-defns) (first published-names)]
                                                        [repo2  (last content-defns) (last published-names)]]]
            (ui/update content-defns assoc :products (list (kt/product repo)))
            (views/publish {:content-defn content-defns
                            :published-name published-names
                            :org org}))
          (with-unique [composite-view (newContentView {:name "composite-view"
                                                        :org org
                                                        :description "Composite Content View"
                                                        :composite 'yes'
                                                        :composite-names published-names})]
            (ui/create composite-view)))))

    (deftest "Add published content-view to an activation-key"
      (with-unique [org (kt/newOrganization {:name "cv-org"})
                    repo (fresh-repo org
                                     "http://inecas.fedorapeople.org/fakerepos/cds/content/safari/1.0/x86_64/rpms/")
                    target-env (kt/newEnvironment {:name "dev" :org org})
                    cv (promote-published-content-view org target-env repo)
                    ak (kt/newActivationKey {:name "ak"
                                             :env target-env
                                             :description "auto activation key"
                                             :content-view (:published-name cv)})]  
        (ui/create ak)
        (assert/is (= (:name (kt/product repo))
                      (browser getText ::views/product-in-cv)))))

    (deftest "Promote content-view containing two published-views"
      (with-unique [org (kt/newOrganization {:name "cv-org"})
                    env (kt/newEnvironment {:name  "dev" :org org})
                    repo1 (fresh-repo org "http://repos.fedorapeople.org/repos/pulp/pulp/v2/stable/6Server/x86_64/")
                    repo2 (fresh-repo org zoo-repo)
                    pub-name1 "publish-name1"
                    pub-name2 "publish-name2"
                    cv1 (kt/newContentView {:name "content-view1"
                                            :org org
                                            :published-name pub-name1})
                    cv2 (kt/newContentView {:name "content-view2"
                                            :org org
                                            :published-name pub-name2})
                    cs (kt/newChangeset {:name "cs"
                                         :env env
                                         :content (list cv1 cv2)})]
        (ui/create-all (list org env cv1 cv2))
        (doseq [repo [repo1 repo2]]
          (create-recursive repo)
          (sync/perform-sync (list repo)))
        (doseq [[repo cv published-names] [[repo1 cv1 pub-name1]
                                           [repo2 cv2 pub-name2]]]
          (ui/update cv assoc :products (list (kt/product repo)))
          (views/publish {:content-defn cv :published-name published-names :org org}))
        (changeset/promote-delete-content cs)))
    
    (deftest "Delete promoted content-view"
      :blockers (open-bz-bugs "960564")
      (with-unique [org (kt/newOrganization {:name "cv-org"})
                    target-env (kt/newEnvironment {:name "dev" :org org})
                    repo (fresh-repo org
                                     "http://inecas.fedorapeople.org/fakerepos/cds/content/safari/1.0/x86_64/rpms/")
                    cv (promote-published-content-view org target-env repo)
                    ak (kt/newActivationKey {:name "ak"
                                              :env target-env
                                              :description "auto activation key"
                                              :content-view (:published-name cv)})
                    deletion-cs (kt/newChangeset {:name "deletion-cs"
                                                  :content (list cv)
                                                  :env target-env
                                                  :deletion? true})]
        (ui/create ak)
        (changeset/promote-delete-content deletion-cs)))
    
     (deftest "Consuming content-view contents on client"
       :blockers (open-bz-bugs "947497")
       (with-unique [org (kt/newOrganization {:name "cv-org"})
                     target-env (kt/newEnvironment {:name "dev" :org org})
                     repo (fresh-repo org
                                      "http://inecas.fedorapeople.org/fakerepos/cds/content/safari/1.0/x86_64/rpms/")
                     cv (promote-published-content-view org target-env repo)                
                     product (kt/product repo)
                     ak (kt/newActivationKey {:name "ak"
                                              :env target-env
                                              :description "auto activation key"
                                              :content-view (:published-name cv)})]
         (ui/create ak)
         (ui/update ak assoc :subscriptions (list  (-> repo kt/product :name)))
         (provision/with-client "consume-content" ssh-conn
           (client/register ssh-conn
                            {:org (:name org)
                             :activationkey (:name ak)})
           (client/sm-cmd ssh-conn :refresh)
           (let [cmd_result (client/run-cmd ssh-conn "yum install cow")]
             (assert/is (->> cmd_result :exit-code (= 0)))))))
     
     
     (deftest "Validate: CV contents should not available on client after deleting it from selected env"
       :blockers (open-bz-bugs "947497")
       (with-unique [org (kt/newOrganization {:name "cv-org"})
                     target-env (kt/newEnvironment {:name "dev" :org org})
                     repo (fresh-repo org
                                      "http://inecas.fedorapeople.org/fakerepos/cds/content/safari/1.0/x86_64/rpms/")
                     cv (promote-published-content-view org target-env repo)                
                     product (kt/product repo)
                     ak (kt/newActivationKey {:name "ak"
                                              :env target-env
                                              :description "auto activation key"
                                              :content-view (:published-name cv)})]
         (ui/create ak)
         (ui/update ak assoc :subscriptions (list  (-> repo kt/product :name)))
         (provision/with-client "reg-sys-with-ak" ssh-conn
           (client/register ssh-conn
                            {:org (:name org)
                             :activationkey (:name ak)})
           (let [deletion-cs (-> {:name "deletion-cs"
                                  :content (list cv)
                                  :env target-env
                                  :deletion? true}
                               katello/newChangeset
                               uniqueify)]
             (client/sm-cmd ssh-conn :refresh)
             (let [cmd_result (client/run-cmd ssh-conn "yum install cow")]
               (assert/is (->> cmd_result :exit-code (= 0))))
             (changeset/promote-delete-content deletion-cs)
             (client/sm-cmd ssh-conn :refresh)
             (let [cmd_result (client/run-cmd ssh-conn "yum install cow")]
               (assert/is (->> cmd_result :exit-code (= 1))))))))))
