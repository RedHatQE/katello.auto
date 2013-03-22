(ns katello.subscriptions
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            (katello [ui :as ui]
                     [navigation :as nav]
                     [notifications :as notification]
                     [ui-common :as common]
                     [manifest :as manifest])))

;; Locators

(ui/deflocators
  {::import-manifest     "new"
   ::upload              "upload_form_button"
   ::repository-url-text "provider[repository_url]"
   ::new-distributor-disabled "//*[@id='new' and contains(@class,'disabled')]"
   ::choose-file         "provider_contents"
   ::fetch-history-info   "//td/span/span[contains(@class,'check_icon') or contains(@class, 'shield_icon')]"})

;; Nav

(nav/defpages (common/pages))

;; Tasks

(defn upload-manifest
  "Uploads a subscription manifest from the filesystem local to the
   selenium browser. Optionally specify a new repository url for Red
   Hat content- if not specified, the default url is kept. Optionally
   specify whether to force the upload."
  [file-path & [{:keys [repository-url]}]]
  (nav/go-to ::page)
  (when-not (browser isElementPresent ::choose-file)
    (browser click ::import-manifest))
  (when repository-url
    (common/in-place-edit {::repository-url-text repository-url})
    (notification/check-for-success {:match-pred (notification/request-type? :prov-update)}))
  (sel/fill-ajax-form {::choose-file file-path}
                      ::upload)
  (browser refresh)
  ;;now the page seems to refresh on its own, but sometimes the ajax count
  ;; does not update. 
  ;; was using asynchronous notification until the bug https://bugzilla.redhat.com/show_bug.cgi?id=842325 gets fixed.
  (notification/check-for-success {:timeout-ms (* 30 60 1000)}))

(defn upload-new-cloned-manifest
  "Clones the manifest at orig-file-path and uploads it to the current org."
  [orig-file-path & [{:keys [repository-url] :as m}]]
  (let [clone-loc (manifest/new-tmp-loc)]
    (manifest/clone orig-file-path clone-loc)
    (upload-manifest clone-loc m)))

(defn upload-manifest-import-history?
  "Returns true if after an manifest import the history is updated."
  []
  (nav/go-to ::import-history-page)
  (browser isElementPresent ::fetch-history-info))

(defn new-distributor-disabled?
  "Returns true if the new distributor button is disabled"
  []
  (nav/go-to ::distributors-page)
  (browser isElementPresent ::new-distributor-disabled))
