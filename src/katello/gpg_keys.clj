(ns katello.gpg-keys
  (:require (katello [navigation :as nav]
                     [ui :as ui]
                     [ui-common :as common]
                     [notifications :as notification])
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

;; Nav

(nav/defpages (common/pages)
  [::page
   [::new-page [] (browser click ::new)]
   [::named-page [gpg-key-name] (nav/choose-left-pane gpg-key-name)]])

;;Tasks

(defn create [name & [{:keys [filename contents]}]]
  (assert (not (and filename contents))
          "Must specify one one of :filename or :contents.")
  (assert (string? name))
  (nav/go-to ::new-page)
  (if filename
    (sel/fill-ajax-form {::name-text name
                         ::file-upload-text filename}
                        ::upload-button)
    (sel/fill-ajax-form {::name-text name
                         ::content-text contents}
                        ::save))
  (notification/check-for-success))


(defn remove 
  "Deletes existing GPG keys"
  [gpg-key-name]
  (nav/go-to ::named-page {:gpg-key-name gpg-key-name})
  (browser click ::remove-link )
  (browser click ::ui/confirmation-yes)
  (notification/check-for-success))
