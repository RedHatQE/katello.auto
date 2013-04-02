(ns katello.tests.content_views
  (:require (katello [conf :refer [with-org]]
                     [content-view-definitions :as views]
                     [notifications :as notifications]
                     [organizations :as organization]
                     [providers :as provider]
                     [repositories :as repo]
                     [tasks :refer :all]
                     [ui-common :as common]
                     [validation :refer :all])
            [katello.tests.organizations :refer [create-org-with-provider-and-repo]]
            [test.tree.script :refer :all]
            [bugzilla.checker :refer [open-bz-bugs]]))

;; Functions

;; Tests
(defgroup content-views-tests

  (deftest "Create a new content view definition"
    (with-unique [env-name "auto-env"
                  org-name "real-org"
                  view-definition-name "content-view"]
      (organization/create org-name {:initial-env-name env-name})
      (organization/switch org-name)
      (views/create-content-view-definition view-definition-name {:description "Automatically created."})
      (notifications/check-for-success)))

  (deftest "Create a new content view definition using the same name"
    (with-unique [env-name "auto-env"
                  org-name "real-org"
                  view-definition-name "content-view"]
      (organization/create org-name {:initial-env-name env-name})
      (organization/switch org-name)
      (views/create-content-view-definition view-definition-name {:description "Automatically created."})
      (notifications/check-for-success)
      (expecting-error (common/errtype ::notifications/label-taken-error)
                       (views/create-content-view-definition view-definition-name {:description "Automatically created."}))))

  (deftest "Delete a content view definition"
    (with-unique [env-name "auto-env"
                  org-name "real-org"
                  view-definition-name "content-view"
                  modified-definition-name "modified"]
      (organization/create org-name {:initial-env-name env-name})
      (organization/switch org-name)
      (views/create-content-view-definition view-definition-name {:description "Automatically created."})
      (notifications/check-for-success)
      (views/edit-content-view-definition view-definition-name {:new-name modified-definition-name :description "This has been modified"})
      (notifications/check-for-success)))

  (deftest "Delete a content view definition"
    (with-unique [env-name "auto-env"
                  org-name "real-org"
                  view-definition-name "content-view"]
      (organization/create org-name {:initial-env-name env-name})
      (organization/switch org-name)
      (views/create-content-view-definition view-definition-name {:description "Automatically created."})
      (notifications/check-for-success)
      (views/delete-content-view-definition view-definition-name)
      (notifications/check-for-success)))

  (deftest "Create a new content view definition and add a product"
    (with-unique [org-name "auto-org"
                  published-name "auto-published-view"
                  provider-name "auto-provider"
                  product-name "auto-product"
                  repo-name "auto-repo"
                  view-definition-name "auto-view-definition"]
      (let [repo-url "http://repos.fedorapeople.org/repos/pulp/pulp/v2/stable/6Server/"]
        (create-org-with-provider-and-repo org-name provider-name product-name repo-name repo-url))
      (views/create-content-view-definition view-definition-name {:description "Automatically created."})
      (views/add-product-to-content-view view-definition-name {:prod-name product-name})
      (views/publish-content-view-definition view-definition-name published-name "Published automatically.")))

  (deftest "Create a new content view definition and add a product"
    (with-unique [composite-view-name "composite-view"
                  org-name "auto-org"
                  published-name "auto-published-view"
                  provider-name "auto-provider"
                  product-name "auto-product"
                  repo-name "auto-repo"
                  view-definition-name "auto-view-definition"]
      (let [repo-url "http://repos.fedorapeople.org/repos/pulp/pulp/v2/stable/6Server/"]
        (create-org-with-provider-and-repo org-name provider-name product-name repo-name repo-url))
      (views/create-content-view-definition view-definition-name {:description "Automatically created."})
      (views/add-product-to-content-view view-definition-name {:prod-name product-name})
      (views/publish-content-view-definition view-definition-name published-name "Published automatically.")
      (views/create-content-view-definition composite-view-name {:description "Composite Content View" :composite 'yes' :composite-name published-name})))
)
