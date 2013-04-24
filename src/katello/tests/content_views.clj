(ns katello.tests.content-views
  (:require (katello [conf :refer [*session-org*] :as conf]
                     [ui :as ui]
                     [rest :as rest]
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
            [katello.tests.useful :refer [fresh-repo create-recursive]]
            [katello.tests.organizations :refer [setup-custom-org-with-content]]
            [katello :refer [newOrganization newProvider newProduct newRepository newContentView]]
            [bugzilla.checker :refer [open-bz-bugs]]))

;; Functions

;; Tests

(defgroup content-views-tests

  (deftest "Create a new content view definition"
    (-> {:name "view-def" :org *session-org*}
      katello/newContentView
      uniqueify
      ui/create))
  
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
  
  (deftest "Clone content view definition"
     (with-unique [content-def (kt/newContentView {:name "con-def"
                                                   :org conf/*session-org*})]
        (ui/create content-def)
        (views/clone content-def (update-in content-def [:name] #(str % "-clone")))))
  
  (deftest "Publish content view definition"
    (with-unique [content-def (kt/newContentView {:name "con-def"
                                                  :org conf/*session-org*})]
      (ui/create content-def)
      (views/publish {:name content-def 
                      :published-name "pub-name" 
                      :org *session-org*})))
  

  (deftest "Create a new content-view/composite definition and add a product"
    :data-driven true
    
    (fn [composite?]
      (with-unique [org (newOrganization {:name "auto-org"})
                    content-view (kt/newContentView {:name "auto-view-definition" 
                                                     :org org})
                    repo (fresh-repo org "http://repos.fedorapeople.org/repos/pulp/pulp/v2/stable/6Server/")
                    published-name "pub-name"
                    composite-view (kt/newContentView {:name "composite-view" 
                                                       :org org 
                                                       :description "Composite Content View" 
                                                       :composite 'yes' 
                                                       :composite-name published-name})]
        (ui/create-all (list org content-view))
        (create-recursive repo)
        (ui/update content-view assoc :products (list (kt/product repo)))
        (views/publish {:name content-view 
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
                     repo (fresh-repo org "http://repos.fedorapeople.org/repos/pulp/pulp/v2/stable/6Server/")
                     content-defn1 (assoc content-defn :products (kt/product repo))]
         (ui/create-all (list org content-defn))
         (create-recursive repo)                               
         (ui/update content-defn assoc :products (list (kt/product repo)))
         (ui/update content-defn1 dissoc :products)))


   (deftest "Create composite content-definition with two products"
     (with-unique [org (kt/newOrganization {:name "auto-org"})]
       (let [repo1 (fresh-repo org "http://repos.fedorapeople.org/repos/pulp/pulp/v2/stable/6Server/")
             repo2 (fresh-repo org "http://inecas.fedorapeople.org/fakerepos/zoo/")
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
           (views/publish {:name content-defns 
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
                   pub-name "publish-name"
                   target-env (kt/newEnvironment {:name "dev" :org org})
                   ak (kt/newActivationKey {:name "ak"
                                            :env target-env
                                            :description "auto activation key"
                                            :content-view pub-name})
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
       (views/publish {:name cv 
                       :published-name pub-name 
                       :description "test pub" 
                       :org org})
       (changeset/promote-delete-content cs)
       (ui/create ak))))
