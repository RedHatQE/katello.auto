(ns katello.tests.content-views
  (:require (katello [conf :refer [*session-org*] :as conf]
                     [ui :as ui]
                     [content-view-definitions :as views]
                     [notifications :as notifications]
                     [organizations :as organization]
                     [providers :as provider]
                     [repositories :as repo]
                     [tasks :refer :all]
                     [ui-common :as common]
                     [validation :refer :all])
            [test.tree.script :refer :all]
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

  (deftest "Edit a content view definition"
      (with-unique [content-def (katello/newContentView {:name "con-def"
                                                         :org conf/*session-org*})]
        (ui/create content-def)
        (ui/update assoc :name "modified_def")))
  
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
  
  (deftest "Create a new content view definition and add a product"
     (with-unique [org  (newOrganization {:name "auto-org"})
                  provider (newProvider {:name "auto-provider" :org org})
                  product (newProduct {:name "auto-product" :provider provider})
                  repo (newRepository {:name "auto-repo" :product product
                                       :url "http://repos.fedorapeople.org/repos/pulp/pulp/v2/stable/6Server/"})
                  view-definition (newContentView {:name "auto-view-definition" :org org})]
      (let [create-all #(ui/create-all (list org provider product repo view-definition))]
        (create-all))
      (views/add-product {:name view-definition :prod-name product})
      (views/publish {:name view-definition :published-name "pub-name" :org *session-org*})))

  (deftest "Create a new content-view/composite definition and add a product"
    :data-driven true
    
    (fn [composite?]
      (with-unique [org  (newOrganization {:name "auto-org"})
                    provider (newProvider {:name "auto-provider" :org org})
                    product (newProduct {:name "auto-product" :provider provider})
                    repo (newRepository {:name "auto-repo" :product product
                                         :url "http://repos.fedorapeople.org/repos/pulp/pulp/v2/stable/6Server/"})
                    view-definition (newContentView {:name "auto-view-definition" :org org})
                    published-name "pub-name"
                    composite-view (newContentView {:name "composite-view" :org org :description "Composite Content View" :composite 'yes' :composite-name published-name})]
        (let [create-all #(ui/create-all (list org provider product repo view-definition))]
          (create-all))
        (views/add-product {:name view-definition :prod-name product})
        (views/publish {:name view-definition :published-name published-name :org *session-org*})
        (when composite?
          (ui/create composite-view))))
    
    [[true]
     [false]])
  )

