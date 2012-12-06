(ns katello.gpg-keys
  (:require (katello [navigation :as nav]
                     [ui-common :as ui]
                     [notifications :as notification])
            [com.redhat.qe.auto.selenium.selenium :as sel]))

;; Locators

(swap! ui/uimap merge
       {:gpg-key-name-text        "gpg_key_name"
        :gpg-key-file-upload-text "gpg_key_content_upload"
        :gpg-key-upload-button    "upload_gpg_key"
        :gpg-key-content-text     "gpg_key_content"
        :gpg-keys                 "//a[.='GPG Keys']"
        :gpg-keys-save            "save_gpg_key"
        :new-gpg-key              "new"
        :remove-gpg-key           (ui/link "Remove GPG Key")})

;; Nav

(nav/graft-page-tree
 :repositories-tab
 [:gpg-keys-page [] (sel/browser clickAndWait :gpg-keys)
  [:new-gpg-key-page [] (sel/browser click :new-gpg-key)]
  [:named-gpgkey-page [gpg-key-name] (nav/choose-left-pane  gpg-key-name)]])


;;Tasks


(defn create [name & [{:keys [filename contents]}]]
  (assert (not (and filename contents))
          "Must specify one one of :filename or :contents.")
  (assert (string? name))
  (nav/go-to :new-gpg-key-page)
  (if filename
    (sel/fill-ajax-form {:gpg-key-name-text name
                         :gpg-key-file-upload-text filename}
                        :gpg-key-upload-button)
    (sel/fill-ajax-form {:gpg-key-name-text name
                         :gpg-key-content-text contents}
                        :gpg-keys-save))
  (notification/check-for-success))


(defn remove 
  "Deletes existing GPG keys"
  [gpg-key-name]
  (nav/go-to :named-gpgkey-page {:gpg-key-name gpg-key-name})
  (sel/browser click :remove-gpg-key )
  (sel/browser click :confirmation-yes)
  (notification/check-for-success))