(ns katello.gpg-keys
  (:require katello
            (katello [navigation :as nav]
                     [ui :as ui]
                     [rest :as rest]
                     [ui-common :as common]
                     [notifications :as notification]
                     [conf :refer [config]]
                     [tasks :as tasks])
            [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]])
  (:refer-clojure :exclude [remove]))

;; Locators

(ui/defelements :katello.deployment/any []
  {::name-text        "gpg_key_name"
   ::file-upload-text "gpg_key_content_upload"
   ::upload-button    "upload_gpg_key"
   ::content-text     "gpg_key_content"
   ::save             "save_gpg_key"
   ::new              "new"
   ::remove-link      (ui/remove-link "gpg_keys")})

(sel/template-fns
 {gpgkey-product-association  "//ul[contains (@class,'bordered-table')]/div[contains (.,'%s')]"})

;; Nav

(nav/defpages :katello.deployment/any katello.menu
  [::page
   [::new-page (nav/browser-fn (click ::new))]
   [::named-page (fn [gpg-key] (nav/choose-left-pane gpg-key))]])

;;Tasks

(defn- create [{:keys [name url contents org]}]
  (assert (not (and url contents))
          "Must specify one one of :url or :contents.")
  (assert (string? name))
  (nav/go-to ::new-page org)
  (if url
    (sel/->browser (setText ::name-text name)
                   (attachFile ::file-upload-text url)
                   (click ::upload-button))
    (sel/fill-ajax-form {::name-text name
                         ::content-text contents}
                        ::save))
  (notification/check-for-success {:match-pred (notification/request-type? :gpg-keys-create)}))


(defn- delete 
  "Deletes existing GPG keys"
  [gpg-key]
  (nav/go-to gpg-key)
  (browser click ::remove-link )
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success {:match-pred (notification/request-type? :gpg-keys-destroy)}))

(extend katello.GPGKey
  ui/CRUD {:create create
           :delete delete}

  rest/CRUD (let [query-url (partial rest/url-maker [["api/organizations/%s/gpg_keys" [#'katello/org]]])
                  id-url (partial rest/url-maker [["api/gpg_keys/%s" [identity]]])]
              {:id rest/id-field
               :query (partial rest/query-by-name query-url)
               :read (partial rest/read-impl id-url)
               :create (fn [{:keys [name contents url org] :as gpg-key}]
                         {:pre [(instance? katello.Organization org)
                                (not (and url contents))]}
                         (merge gpg-key
                                (rest/http-post (query-url gpg-key) 
                                 {:body {:gpg_key {:name name
                                                   :content (if url (slurp url) contents)}}})))})
  
  nav/Destination {:go-to (partial nav/go-to ::named-page)}
  
  tasks/Uniqueable tasks/entity-uniqueable-impl)

  
