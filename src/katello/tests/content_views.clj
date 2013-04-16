(ns katello.tests.content-views
  (:require (katello [conf :refer [*session-org*] :as conf]
                     [ui :as ui]
                     [rest :as rest]
                     [content-view-definitions :as views]
                     [notifications :as notifications]
                     [organizations :as organization]
                     [providers :as provider]
                     [repositories :as repo]
                     [tasks :refer :all]
                     [ui-common :as common]
                     [validation :refer :all])
            [test.tree.script :refer :all]
            [katello :as kt]
            [katello.tests.useful :refer [fresh-repo create-recursive]]
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
    (with-unique [content-def (katello/newContentView {:name "con-def"
                                                       :org conf/*session-org*})]
      (ui/create content-def)
      (expecting-error (common/errtype ::notifications/name-taken-error)
                       (ui/create content-def))))
  
  (deftest "Delete a content view definition"
    (doto (-> {:name "view-def" :org *session-org*}
            katello/newContentView
            uniqueify)
      (ui/create)
      (ui/delete)))
  
  (deftest "Clone content view definition"
     (with-unique [content-def (katello/newContentView {:name "con-def"
                                                         :org conf/*session-org*})]
        (ui/create content-def)
        (views/clone content-def (update-in content-def [:name] #(str % "-clone")))))
  
  (deftest "Publish content view definition"
    (with-unique [content-def (katello/newContentView {:name "con-def"
                                                       :org conf/*session-org*})]
      (ui/create content-def)
      (views/publish {:name content-def :published-name "pub-name" :org *session-org*})))
  

  (deftest "Create a new content-view/composite definition and add a product"
    :data-driven true
    
    (fn [composite?]
      (with-unique [org (newOrganization {:name "auto-org"})
                    view-definition (newContentView {:name "auto-view-definition" :org org})
                    published-name "pub-name"
                    composite-view (newContentView {:name "composite-view" :org org :description "Composite Content View" :composite 'yes' :composite-name published-name})]      
        (ui/create-all (list org view-definition))
        (let [repo (fresh-repo org "http://repos.fedorapeople.org/repos/pulp/pulp/v2/stable/6Server/")]
          (create-recursive repo)
          (views/add-product {:name view-definition :prod-name (kt/product repo)}))
        (views/publish {:name view-definition :published-name published-name :org *session-org*})
        (when composite?
          (ui/create composite-view))))
    
    [[true]
     [false]])
  
   (deftest "Edit a content view definition"
    (with-unique [org (newOrganization {:name "auto-org"})
                  content-definition (newContentView {:name "auto-view-definition" :description "new description" :org org})
                  modified-name "mod-name"]
      (ui/create-all (list org content-definition))
      (ui/update content-definition assoc :name modified-name :description "modified description")))
   
   
   (deftest "Remove complete product or a repo from content-view-defnition"
     :data-driven "true"
     
     (fn [repo?]
       (with-unique [org (newOrganization {:name "auto-org"})
                     content-defn (newContentView {:name "auto-view-definition" :org org})
                     published-name "pub-name"
                     composite-view (newContentView {:name "composite-view" 
                                                     :org org 
                                                     :description "Composite Content View" 
                                                     :composite 'yes' :composite-name published-name})]
         (ui/create-all (list org content-defn))
         (let [repo (fresh-repo org "http://repos.fedorapeople.org/repos/pulp/pulp/v2/stable/6Server/")]
           (create-recursive repo)                               
           (views/add-product {:name content-defn :prod-name (kt/product repo)})
           (views/remove-product content-defn)
           (when repo?
             (views/remove-repo content-defn {:repo-name (kt/repository repo)})))))
     
     [[true]
      [false]])   
   )
       
                   
