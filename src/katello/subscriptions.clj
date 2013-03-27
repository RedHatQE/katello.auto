(ns katello.subscriptions
  (:require [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            [katello :as kt]
            (katello [ui :as ui]
                     [rest :as rest]
                     [navigation :as nav]
                     [tasks :as tasks]
                     [notifications :as notification]
                     [ui-common :as common]
                     [manifest :as manifest])))

;; Locators

(ui/deflocators
  {::import-manifest     "new"
   ::upload              "upload_form_button"
   ::repository-url-text "provider[repository_url]"
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
  [{:keys [file-path url provider]}]
  (nav/go-to ::page {:org (:org provider)})
  (when-not (browser isElementPresent ::choose-file)
    (browser click ::import-manifest))
  (when url
    (common/in-place-edit {::repository-url-text url})
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
  [{:keys [file-path url] :as m}]
  (let [clone-loc (manifest/new-tmp-loc)
        clone (assoc m :file-path clone-loc)]
    (manifest/clone file-path clone-loc)
    (upload-manifest clone)))

(defn upload-manifest-import-history?
  "Returns true if after an manifest import the history is updated."
  []
  (nav/go-to ::import-history-page)
  (browser isElementPresent ::fetch-history-info))
  
(extend katello.Manifest
  ui/CRUD {:create upload-manifest}
  rest/CRUD {:create (fn [{:keys [url file-path] :as m}]
                       (merge m
                              (let [provid (-> m :provider rest/get-id)]
                                (do (rest/http-put (rest/api-url (format "/api/providers/%s" provid))
                                                   {:body {:provider {:repository_url url}}})
                                    (rest/http-post (rest/api-url (format "/api/providers/%s/import_manifest" provid))
                                                    {:multipart [{:name "import"
                                                                  :content (clojure.java.io/file file-path)
                                                                  :mime-type "application/zip"
                                                                  :encoding "UTF-8"}]})))))}
  tasks/Uniqueable {:uniques (fn [m]
                               (repeatedly (fn [] (let [newpath (manifest/new-tmp-loc)]
                                                    (manifest/clone (:file-path m) newpath)
                                                    (assoc m :file-path newpath)))))})
