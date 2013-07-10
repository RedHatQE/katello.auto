(ns katello.tests.content-views
  (:require (katello [conf :refer [*session-org*] :as conf]
                     [ui :as ui]
                     [rest :as rest]
                     [client :as client]
                     [content-view-definitions :as views]
                     [notifications :as notifications]
                     [navigation :as nav]
                     [organizations :as organization]
                     [providers :as provider]
                     [repositories :as repo]
                     [sync-management :as sync]
                     [changesets :as changeset]
                     [tasks :refer :all]
                     [ui-common :as common]
                     [validation :refer :all]
                     [blockers :refer [bz-bugs auto-issue]])
            [test.tree.script :refer [deftest defgroup]]
            [katello :as kt]
            [katello.client.provision :as provision]
            [test.assert :as assert]
            [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            [katello.tests.useful :refer [fresh-repo create-recursive]]
            [katello.tests.organizations :refer [setup-custom-org-with-content]]
            [katello :refer [newOrganization newProvider newProduct newRepository newContentView]]
            ))

;; Functions
(defn promote-published-content-view
  "Function to promote published content view"
  [org target-env repo]
  (with-unique [cv (kt/newContentView {:name "content-view"
                                       :org org
                                       :published-name "publish-name"})
                
                cs (kt/newChangeset {:name "cs"
                                     :env target-env
                                     :content (list cv)})]
    (ui/create-all-recursive (list org target-env))
    (ui/create cv)
    (create-recursive repo)
    (sync/perform-sync (list repo))
    (ui/update cv assoc :products (list (kt/product repo)))
    (views/publish {:content-defn cv
                    :published-name (:published-name cv)
                    :description "test pub"
                    :org org})
    (changeset/promote-delete-content cs)
    cv))

(defn promote-published-composite-view
  "Function to promote composite content view that contains two published views"
  [org env repo1 repo2 cv1 cv2]
  (ui/create-all (list org env cv1 cv2))
  (doseq [repo [repo1 repo2]]
    (create-recursive repo)
    (sync/perform-sync (list repo)))
  (doseq [[repo cv published-names] [[repo1 cv1 (:published-name cv1)]
                                     [repo2 cv2 (:published-name cv2)]]]
    (ui/update cv assoc :products (list (kt/product repo)))
    (views/publish {:content-defn cv :published-name published-names :org org}))
  (with-unique [composite-view (newContentView {:name "composite-view"
                                                :org org
                                                :description "Composite Content View"
                                                :published-name "publish-composite"
                                                :composite true
                                                :composite-names (list cv1 cv2)})]
    (ui/create composite-view)
    (views/publish {:content-defn composite-view :published-name (:published-name composite-view) :org org})
    (with-unique [composite-cs (katello/newChangeset {:name "composite-cs"
                                                      :content (list composite-view)
                                                      :env env})]
      (changeset/promote-delete-content composite-cs)
      composite-view)))

                      
;; Tests

(let [success #(-> % :type (= :success))
      pulp-repo "http://repos.fedorapeople.org/repos/pulp/pulp/v2/stable/6Server/"
      zoo-repo "http://inecas.fedorapeople.org/fakerepos/zoo/"]

  (defgroup content-views-tests

    (deftest "Create a new content view definition"
      :uuid "43016c01-3f46-b164-3ffb-4c31e0d3339d"
      (-> {:name "view-def" :org *session-org*}
          katello/newContentView
          uniqueify
          ui/create))

    (deftest "Create a new content view definition w/ i18n characters"
      :uuid "79319b58-3934-80e4-7103-a307fc8eec70"
      :blockers (bz-bugs "953594")
      :data-driven true

      (fn [view-name]
        (-> {:name view-name :org *session-org*}
            katello/newContentView
            uniqueify
            ui/create))
      (map list i18n-chars))

    (deftest "Create a new content view with a blank name"
      :uuid "e6244ec7-6c90-3284-2e2b-e8e81d75f9fd"
      :tcms "248517"

      (expecting-error (common/errtype ::notifications/name-cant-be-blank)
                       (-> {:name "" :org *session-org*}
                           katello/newContentView
                           ui/create)))

    (deftest "Create a new content view with a long name"
      :uuid "ba7e1f58-bf60-2954-a8cb-5185c011b9f0"
      :data-driven true
      :tcms "248518"

      (fn [view-name expected-res]
        (let [content-view (katello/newContentView {:name view-name :org *session-org*})]
          (expecting-error expected-res (ui/create content-view))))

      [[(random-ascii-string 129) (common/errtype ::notifications/name-128-char-limit)]
       [(random-ascii-string 128) success]])

    (deftest "Create a new content view definition using the same name"
      :uuid "32447769-82b4-1334-bdab-8d40d7012286"
      (with-unique [content-def (kt/newContentView {:name "con-def"
                                                    :org conf/*session-org*})]
        (ui/create content-def)
        (expecting-error (common/errtype ::notifications/name-taken-error)
                         (ui/create content-def))))

    (deftest "Delete a content view definition"
      :uuid "79c7d67f-5fca-dcc4-7e33-73c8bf413c67"
      (doto (-> {:name "view-def" :org *session-org*}
                kt/newContentView
                uniqueify)
        (ui/create)
        (ui/delete)))

    (deftest "Clone empty content view definition"
      :uuid "2184af79-0ffb-3cf4-52bb-48b28eefe34c"
      (with-unique [content-def (kt/newContentView {:name "con-def"
                                                    :org conf/*session-org*})]
        (ui/create content-def)
        (views/clone content-def (update-in content-def [:name] #(str % "-clone"))))

      (deftest "Clone content view definition with content"
        :uuid "3a536c98-1829-6cb4-24e3-ff68de2e095d"
        :tcms "248743"
        
        (with-unique [content-def (kt/newContentView {:name "con-def"
                                                      :org conf/*session-org*})
                      repo (fresh-repo (kt/org content-def) pulp-repo)]
          (ui/create content-def)
          (create-recursive repo)
          (ui/update content-def assoc :products (list (kt/product repo)))
          (views/clone content-def (update-in content-def [:name] #(str % "-clone"))))))


    (deftest "Publish content view definition"
      :uuid "b71674d7-0e86-fe04-39f3-f408cb2a95bc"
      (with-unique [content-def (kt/newContentView {:name "con-def"
                                                    :published-name "publish-name"
                                                    :org conf/*session-org*})]
        (ui/create content-def)
        (views/publish {:content-defn content-def
                        :published-name (:published-name content-def)
                        :org *session-org*})))

    (deftest "Published content view name links to content search page"
      :uuid "16fb0291-7312-6ab4-e92b-063d776f837b"
      (with-unique [content-def (kt/newContentView {:name "con-def"
                                                    :published-name "publish-name"
                                                    :org conf/*session-org*})]
        (ui/create content-def)
        (views/publish {:content-defn content-def 
                        :published-name (:published-name content-def)
                        :org *session-org*})
        (let [{:strs [href]} (browser getAttributes (views/publish-view-name (:published-name content-def)))]
          (assert (and (.startsWith href "/katello/content_search")
                       (.contains href (:published-name content-def)))))))
    
    (deftest "Create a new content-view/composite definition and add a product"
      :uuid "1f2416e4-ad73-49e4-dad3-6c529caeb0bb"
      :data-driven true

      (fn [composite?]
        (with-unique [org (newOrganization {:name "auto-org"})
                      content-view (kt/newContentView {:name "auto-view-definition"
                                                       :published-name "publish-name"
                                                       :org org})
                      repo (fresh-repo org pulp-repo)
                      composite-view (kt/newContentView {:name "composite-view"
                                                         :org org
                                                         :description "Composite Content View"
                                                         :composite 'yes'
                                                         :composite-name content-view})]
          (ui/create-all (list org content-view))
          (create-recursive repo)
          (ui/update content-view assoc :products (list (kt/product repo)))
          (views/publish {:content-defn content-view
                          :published-name (:published-name content-view)
                          :org *session-org*})
          (when composite?
            (ui/create composite-view))))

      [[true]
       [false]])

    (deftest "Edit a content view definition"
      :uuid "f8de7fae-2cdf-4854-4793-50c33371e491"
      (with-unique [org (kt/newOrganization {:name "auto-org"})
                    content-definition (kt/newContentView {:name "auto-view-definition"
                                                           :description "new description"
                                                           :org org})
                    modified-name "mod-name"]
        (ui/create-all (list org content-definition))
        (ui/update content-definition assoc :name modified-name :description "modified description")))


    (deftest "Remove complete product or a repo from content-view-defnition"
      :uuid "5439b54f-e679-19b4-fd93-3fbc32c96b14"
      (with-unique [org (kt/newOrganization {:name "auto-org"})
                    content-defn (kt/newContentView {:name "auto-view-definition"
                                                     :org org})
                    repo1 (fresh-repo org "http://inecas.fedorapeople.org/fakerepos/zoo/")
                    repo2 (fresh-repo org "http://inecas.fedorapeople.org/fakerepos/cds/content/safari/1.0/x86_64/rpms/")]
        (ui/create-all (list org content-defn))
        (doseq [repo [repo1 repo2]]
          (create-recursive repo))
        (-> content-defn (ui/update assoc :products (list (kt/product repo1)))
          (ui/update dissoc :products))
        (-> content-defn (ui/update assoc :repos (list (kt/repository repo2)))
          (ui/update dissoc :repos))))

    (deftest "Create composite content-definition with two products"
      :uuid "9463a161-8d9b-9cc4-f09b-c011b0cd6c53"
      (with-unique [org (kt/newOrganization {:name "auto-org"})
                    cv1 (kt/newContentView {:name "content-view1"
                                            :org org
                                            :published-name "publish-name1"})
                    cv2 (kt/newContentView {:name "content-view2"
                                            :org org
                                            :published-name "publish-name2"})]
        (let [repo1 (fresh-repo org pulp-repo)
              repo2 (fresh-repo org zoo-repo)]
          (ui/create-all (list org cv1 cv2))
          (doseq [repo [repo1 repo2]]
            (create-recursive repo))
          (doseq [[repo cv published-names] [[repo1  cv1 (:published-name cv1)]
                                             [repo2  cv2 (:published-name cv2)]]]
            (ui/update cv assoc :products (list (kt/product repo)))
            (views/publish {:content-defn cv
                            :published-name published-names
                            :org org}))
          (with-unique [composite-view (newContentView {:name "composite-view"
                                                        :org org
                                                        :description "Composite Content View"
                                                        :composite 'yes'
                                                        :composite-names (list cv1 cv2)})]
            (ui/create composite-view)))))

    (deftest "Add published content-view to an activation-key"
      :uuid "fcf1634a-fa31-3664-fecb-25b181009147"
      (with-unique [org (kt/newOrganization {:name "cv-org"})
                    target-env (kt/newEnvironment {:name "dev" :org org})]
        (let [repo (fresh-repo org
                               "http://inecas.fedorapeople.org/fakerepos/cds/content/safari/1.0/x86_64/rpms/")
              cv (promote-published-content-view org target-env repo)
              ak (kt/newActivationKey {:name (uniqueify "ak")
                                       :env target-env
                                       :description "auto activation key"
                                       :content-view cv})]
          (ui/create ak)
          (assert/is (= (:name (kt/product repo))
                        (browser getText ::views/product-in-cv))))))
    
    (deftest "Promote content-view containing two published-views"
      :uuid "0151b513-6248-7e04-97eb-1bb43c81b592"
      (with-unique [org (kt/newOrganization {:name "cv-org"})
                    env (kt/newEnvironment {:name  "dev" :org org})
                    cv1 (kt/newContentView {:name "content-view1"
                                            :org org
                                            :published-name "publish-name1"})
                    cv2 (kt/newContentView {:name "content-view2"
                                            :org org
                                            :published-name "publish-name2"})
                    cs (kt/newChangeset {:name "cs"
                                         :env env
                                         :content (list cv1 cv2)})]
        (ui/create-all (list org env cv1 cv2))
        (let [repo1 (fresh-repo org "http://repos.fedorapeople.org/repos/pulp/pulp/v2/stable/6Server/x86_64/")
              repo2 (fresh-repo org "http://inecas.fedorapeople.org/fakerepos/zoo/")]
          (doseq [repo [repo1 repo2]]
            (create-recursive repo)
            (sync/perform-sync (list repo)))
          (doseq [[repo cv published-names] [[repo1 cv1 (:published-name cv1)]
                                             [repo2 cv2 (:published-name cv2)]]]
            (ui/update cv assoc :products (list (kt/product repo)))
            (views/publish {:content-defn cv :published-name published-names :org org}))
          (changeset/promote-delete-content cs))))
    
    (deftest "Delete promoted content-view"
      :uuid "55371086-281b-5654-2853-f69f0216ef62"
      :blockers (bz-bugs "960564")
      (with-unique [org (kt/newOrganization {:name "cv-org"})
                    target-env (kt/newEnvironment {:name "dev" :org org})]
        (let [repo (fresh-repo org
                               "http://inecas.fedorapeople.org/fakerepos/cds/content/safari/1.0/x86_64/rpms/")
              cv (promote-published-content-view org target-env repo)
              ak (kt/newActivationKey {:name (uniqueify "ak")
                                       :env target-env
                                       :description "auto activation key"
                                       :content-view cv})
              deletion-cs (kt/newChangeset {:name (uniqueify "deletion-cs")
                                            :content (list cv)
                                            :env target-env
                                            :deletion? true})]
          (ui/create ak)
          (changeset/promote-delete-content deletion-cs))))
      
    (deftest "Consuming content-view contents on client"
      :uuid "87a73413-11a2-8434-2093-53c408be2b82"
      :blockers (bz-bugs "947497")
      (with-unique [org (kt/newOrganization {:name "cv-org"})
                    target-env (kt/newEnvironment {:name "dev" :org org})]
        (let [repo (fresh-repo org
                               "http://inecas.fedorapeople.org/fakerepos/cds/content/safari/1.0/x86_64/rpms/")
              cv (promote-published-content-view org target-env repo)                        
              ak (kt/newActivationKey {:name (uniqueify "ak")
                                       :env target-env
                                       :description "auto activation key"
                                       :content-view cv})]
          (ui/create ak)
          (ui/update ak assoc :subscriptions (list  (-> repo kt/product :name)))
          (provision/with-client "consume-content" ssh-conn
            (client/register ssh-conn
                             {:org (:name org)
                              :activationkey (:name ak)})
            (client/sm-cmd ssh-conn :refresh)
            (let [cmd_result (client/run-cmd ssh-conn "yum install -y cow")]
              (assert/is (client/ok? cmd_result)))))))
      
    (deftest "Clone content view definition and consume content from it"
      :uuid "6a356ca9-d3e4-4184-89cb-b72940c480e3"
       (with-unique [org (kt/newOrganization {:name "cv-org"})
                     target-env (kt/newEnvironment {:name "dev", :org org})]   
         (let [repo (fresh-repo org
                                "http://inecas.fedorapeople.org/fakerepos/cds/content/safari/1.0/x86_64/rpms/")
               cv (promote-published-content-view org target-env repo)
               clone (update-in cv [:name] #(str % "-clone"))
               cloned-cv (kt/newContentView {:name clone
                                             :org org
                                             :published-name "cloned-publish-name"})]
           (views/clone cv clone)
           (views/publish {:content-defn clone
                           :published-name (:published-name cloned-cv)
                           :description "test pub"
                           :org org})
           (let [cs (kt/newChangeset {:name (uniqueify "cs")
                                      :env target-env
                                      :content (list cloned-cv)})
                 ak (kt/newActivationKey {:name (uniqueify "ak")
                                          :env target-env
                                          :description "auto activation key"
                                          :content-view cv})]           
             (changeset/promote-delete-content cs)
             (ui/create ak)
             (ui/update ak assoc :subscriptions (list (-> repo kt/product :name)))
             (provision/with-queued-client ssh-conn
               (client/register ssh-conn
                                {:org (:name org)
                                 :activationkey (:name ak)})
               (client/sm-cmd ssh-conn :refresh)
               (let [cmd_result (client/run-cmd ssh-conn "yum install -y cow")]
                 (assert/is (client/ok? cmd_result))))))))
     
    (deftest "Two published-view's of same contents and one of them should be disabled while adding it to composite-view"
      :uuid "7f698537-7525-2e74-8c4b-32445cf0140f"
      :blockers (list (auto-issue "788"))
      (with-unique [org (kt/newOrganization {:name "cv-org"})
                    env (kt/newEnvironment {:name  "dev" :org org})
                    cv1 (kt/newContentView {:name "content-view1"
                                            :org org
                                            :published-name "publish-name1"})
                    cv2 (kt/newContentView {:name "content-view2"
                                            :org org
                                            :published-name "publish-name2"})
                    cs (kt/newChangeset {:name "cs"
                                         :env env
                                         :content (list cv1 cv2)})]
        (ui/create-all (list org env cv1 cv2))
        (let [repo1 (fresh-repo org "http://inecas.fedorapeople.org/fakerepos/cds/content/safari/1.0/x86_64/rpms/")
              repo2 (fresh-repo org "http://inecas.fedorapeople.org/fakerepos/zoo/")]
          (doseq [repo [repo1 repo2]]
            (create-recursive repo)
            (sync/perform-sync (list repo)))
          (doseq [{:keys [published-name] :as cv} [cv1 cv2]]
            (ui/update cv assoc :products (list (kt/product repo1)))
            (ui/update cv assoc :products (list (kt/product repo2)))
            (views/publish {:content-defn cv :published-name (:published-name cv) :org org}))
          (with-unique [composite-view (newContentView {:name "composite-view"
                                                        :org org
                                                        :description "Composite Content View"
                                                        :composite true
                                                        :composite-names (list cv1)})]
            (ui/create composite-view)
            (nav/go-to ::views/content-page composite-view)
            (assert/is (not (browser isChecked (views/composite-view-name (:published-name cv2)))))
            (assert/is (common/disabled? (views/composite-view-name (:published-name cv2))))))))
     
    (deftest "Consume content from composite content view definition"
      :uuid "a4b4fdf5-b38b-f634-5aeb-d09f02769acb"
       :blockers (bz-bugs "961696")
       (with-unique [org (kt/newOrganization {:name "cv-org"})
                     env (kt/newEnvironment {:name  "dev" :org org})
                     cv1 (kt/newContentView {:name "content-view1"
                                             :org org
                                             :published-name "publish-name1"})
                     cv2 (kt/newContentView {:name "content-view2"
                                             :org org
                                             :published-name "publish-name2"})]
         (let [repo1 (fresh-repo org "http://repos.fedorapeople.org/repos/pulp/pulp/v2/stable/6Server/x86_64/")
               repo2 (fresh-repo org "http://inecas.fedorapeople.org/fakerepos/zoo/")
               product1 (-> repo1 kt/product :name)
               product2 (-> repo2 kt/product :name)
               composite-view (promote-published-composite-view org env repo1 repo2 cv1 cv2)
               ak (kt/newActivationKey {:name (uniqueify "ak")
                                        :env env
                                        :description "auto activation key"
                                        :content-view composite-view})]
           (ui/create ak)
           (ui/update ak assoc :subscriptions (list product1 product2))
           (provision/with-queued-client ssh-conn
             (client/register ssh-conn
                              {:org (:name org)
                               :activationkey (:name ak)})
             (client/sm-cmd ssh-conn :refresh)
             (let [cmd_result (client/run-cmd ssh-conn "yum install -y cow")]
               (assert/is (client/ok? cmd_result)))))))
    
    (deftest "Delete part of the composite content view definition and re-promote it"
      :uuid "9fe84637-a8d4-459f-aa63-99bc387a3121"
      :data-driven true
      
      (fn [re-promote?]
        (with-unique [org (kt/newOrganization {:name "cv-org"})
                      env (kt/newEnvironment {:name  "dev" :org org})
                      [cv1 cv2] (kt/newContentView {:name "content-view"
                                                    :org org
                                                    :published-name "publish-name"})
                      deletion-cs (kt/newChangeset {:name "deletion-cs"
                                                    :content (list cv1)
                                                    :env env
                                                    :deletion? true})
                      repromote-cs (kt/newChangeset {:name "repromote-cs"
                                                     :content (list cv1)
                                                     :env env})]
          (let [repo1 (fresh-repo org "http://repos.fedorapeople.org/repos/pulp/pulp/v2/stable/6Server/x86_64/")
                repo2 (fresh-repo org "http://inecas.fedorapeople.org/fakerepos/zoo/")
                product1 (-> repo1 kt/product :name)
                product2 (-> repo2 kt/product :name)
                composite-view (promote-published-composite-view org env repo1 repo2 cv1 cv2)
                ak (kt/newActivationKey {:name (uniqueify "ak")
                                         :env env
                                         :description "auto activation key"
                                         :content-view composite-view})]
            (ui/create ak)
            (ui/update ak assoc :subscriptions (list product1 product2))
            (changeset/promote-delete-content deletion-cs)
            (provision/with-queued-client ssh-conn
              (client/register ssh-conn
                               {:org (:name org)
                                :activationkey (:name ak)})
              (client/sm-cmd ssh-conn :refresh)
              (let [install_result (client/run-cmd ssh-conn "yum install -y cow")]
                (assert/is (client/ok? install_result)))
              (when re-promote?
                (changeset/promote-delete-content repromote-cs)
                (client/sm-cmd ssh-conn :refresh)
                (let [remove_result (client/run-cmd ssh-conn "yum remove -y cow")
                      install_result (client/run-cmd ssh-conn "yum install -y cow")]             
                  (assert/is (client/ok? remove_result))
                  (assert/is (client/ok? install_result))))))))
       
       [[true]
        [false]])
     
     (deftest "Validate: CV contents should not available on client after deleting it from selected env"
      :uuid "5f642606-bbe6-ec14-a4cb-14b97069ff09"
       :blockers (bz-bugs "947497")
       (with-unique [org (kt/newOrganization {:name "cv-org"})
                     target-env (kt/newEnvironment {:name "dev" :org org})]
         (let [repo (fresh-repo org
                                "http://inecas.fedorapeople.org/fakerepos/cds/content/safari/1.0/x86_64/rpms/")
               cv (promote-published-content-view org target-env repo)                
               ak (kt/newActivationKey {:name (uniqueify "ak")
                                        :env target-env
                                        :description "auto activation key"
                                        :content-view cv})]
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
               (let [cmd_result (client/run-cmd ssh-conn "yum install -y cow")]
                 (assert/is (->> cmd_result :exit-code (= 0))))
               (changeset/promote-delete-content deletion-cs)
               (client/sm-cmd ssh-conn :refresh)
               (let [cmd_result (client/run-cmd ssh-conn "yum install -y cat")]
                 (assert/is (->> cmd_result :exit-code (not= 1)))))))))))
