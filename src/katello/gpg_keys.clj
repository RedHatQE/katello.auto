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

(ui/deflocators
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

(nav/defpages (common/pages)
  [::page
   [::new-page [] (browser click ::new)]
   [::named-page [gpg-key] (nav/choose-left-pane gpg-key)]])

;;Tasks

(defn create [{:keys [name url contents org]}]
  (assert (not (and url contents))
          "Must specify one one of :url or :contents.")
  (assert (string? name))
  (nav/go-to ::new-page {:org org})
  (if url
    (sel/->browser (setText ::name-text name)
                   (attachFile ::file-upload-text url)
                   (click ::upload-button))
    (sel/fill-ajax-form {::name-text name
                         ::content-text contents}
                        ::save))
  (notification/check-for-success))


(defn delete 
  "Deletes existing GPG keys"
  [gpg-key]
  (nav/go-to gpg-key)
  (browser click ::remove-link )
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success))

(extend katello.GPGKey
  ui/CRUD {:create create
           :delete delete}

  rest/CRUD (let [query-url (partial rest/url-maker [["api/organizations/%s/gpg_keys" [#'katello/org]]])
                  id-url (partial rest/url-maker [["api/gpg_keys/%s" [identity]]])]
              {:id rest/id-field
               :query (partial rest/query-by-name query-url)
               :read (partial rest/read-impl id-url)})
  
  nav/Destination {:go-to  #(nav/go-to ::named-page {:gpg-key %1
                                                     :org (katello/org %1)})}
  tasks/Uniqueable tasks/entity-uniqueable-impl)

(defn gpg-keys-prd-association?
  [gpg-key-name repo-name]
  (nav/go-to ::named-page {:gpg-key-name gpg-key-name})
  (browser isElementPresent (gpgkey-product-association repo-name)))
  
