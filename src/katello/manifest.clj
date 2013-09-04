(ns ^{:author "jweiss"
      :doc "Contains functions for cloning Katello manifests,
            including changing the id and re-signing the new manifest."}
  katello.manifest
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [com.redhat.qe.auto.selenium.selenium :as sel :refer [browser]]
            [katello :as kt]
            (katello [ui :as ui]
                     [rest :as rest]
                     [navigation :as nav]
                     [tasks :refer [with-unique unique-format tmpfile]]
                     [conf :refer [config]]
                     [tasks :as tasks]
                     [notifications :as notification]
                     [ui-common :as common]
                     [subscriptions :as subs]
                     [sync-management :as sync]
                     [redhat-repositories :as rh-repos]))
  (:import [java.util.zip ZipEntry ZipFile ZipOutputStream ZipInputStream]
           [java.io ByteArrayInputStream ByteArrayOutputStream]
           [org.bouncycastle.openssl PEMReader]
           [java.security Signature]))

(defn zis [f]
  (-> f java.io.FileInputStream. ZipInputStream.))

(defn nested-zip-inputstream
  "Gives a new ZipInputStream for the current position of the given
  one.  The old-zis should be positioned at an entry that is another
  zip file."
  [old-zis]
  (let [baos (ByteArrayOutputStream.)]
    (io/copy old-zis baos)
    (ZipInputStream. (ByteArrayInputStream. (.toByteArray baos)))))

(defn write-zip-entry [zos entry content]
  (.putNextEntry zos (ZipEntry. (.getName entry)))
  (io/copy content zos)
  (.closeEntry zos))

(defn update-in-nested-zip
  "Takes a zip inputstream and returns an inputstream with all the
  files in replacements replaced with their contents."
  [zis path content]
  (if (empty? path) zis
      (with-open [baos (ByteArrayOutputStream.)
                  zos (ZipOutputStream. baos)]
        (let [do-entry (partial write-zip-entry zos)]
          (loop [this-entry (.getNextEntry zis)]
            (when this-entry
              (if (= (first path) (.getName this-entry))
                (if (= (count path) 1)
                  (do-entry this-entry content)
                  ;;nested zip
                  (with-open [this-entry-is (nested-zip-inputstream zis)]
                    (do-entry this-entry (update-in-nested-zip this-entry-is (pop path) content))))
                ;;non-matching file
                (do-entry this-entry zis))
            
              (recur (.getNextEntry zis)))))
        (.finish zos)
        (.toByteArray baos))))

(defn read-bytes [o]
  (with-open [bo (java.io.ByteArrayOutputStream.)]
    (io/copy o bo)
    (.toByteArray bo)))

(defn get-zip-bytes
  "Gets the bytes of consumer_export.zip (to be used in signature)"
  [zis]
  (loop [this-entry (.getNextEntry zis)]
    (cond (not this-entry) nil

          (= (.getName this-entry) "consumer_export.zip")
          (read-bytes zis)

          :else (recur (.getNextEntry zis)))))

(defn new-tmp-loc
  "Returns a unique file path in the system tmp dir, ending in .zip"
  []
  (-> "manifest-%s.zip" unique-format tmpfile))


(def ^{:doc "memoized function to get a signature object for a key at a given url"}
  signer-key-at-url
  (memoize (fn [key-url]
             (let [r (io/reader (java.net.URL. key-url))
                   sig (Signature/getInstance "SHA256withRSA")]
               (.initSign sig (-> r PEMReader. .readObject))
               sig))))

(defn sign
  "Returns a new signature (in a byte array) of the
  consumer_export.zip inside the manifest at the given path.  Signs
  with a key at the given url."
  [manifest-path key-url]
  (let [sig (signer-key-at-url key-url)]
    (.update sig (-> manifest-path zis get-zip-bytes ))
    (.sign sig)))

(def default-consumer-info
  {:name "fake",
   :type {:manifest true, :label "candlepin", :id "ff808081335fdf3d01335fdf48e90004"}})

;; add bouncycastle provider at compile time
(java.security.Security/addProvider (org.bouncycastle.jce.provider.BouncyCastleProvider.))

(defn clone
  "Takes a manifest file location, copies it,and updates it internally
   so that it will be accepted by katello as a new manifest. Also
   specify file output location for the clone (full path string), and
   a url for the key that should be used to sign the manifest (this
   key should be present in the /etc/candlepin/certs/upstream/ dir"
  [source-path dest-path key-url]
  (let [tmp (new-tmp-loc)]
    (io/copy (update-in-nested-zip (zis source-path) 
                                   (list "consumer_export.zip" "export/consumer.json")
                                   (->> (java.util.UUID/randomUUID) .toString (assoc default-consumer-info :uuid) json/json-str))
             (java.io.File. tmp))
    (io/copy (update-in-nested-zip (zis tmp), (list "signature"), (sign tmp key-url))
             (java.io.File. dest-path))))

(defn fetch-manifest [manifest-url]
  "Downloads a manifest and fetches it's location"
  (let [dest (new-tmp-loc)]
    (io/copy (-> manifest-url java.net.URL. io/input-stream)
      (java.io.File. dest))
    dest))

(defn download-original-manifest [redhat-manifest?]
  (let [dest (new-tmp-loc)
        manifest-details (if redhat-manifest? 
                           {:manifest-url (@config :redhat-manifest-url)
                            :repo-url     (@config :redhat-repo-url)}
                           {:manifest-url (@config :fake-manifest-url)
                            :repo-url     (@config :fake-repo-url)})]
    (io/copy (-> manifest-details :manifest-url java.net.URL. io/input-stream)
             (java.io.File. dest))
    (kt/newManifest {:file-path dest
                     :url (manifest-details :repo-url)})))

(defn prepare-org
  "Clones a manifest, uploads it to the given org (via api), and then
   enables and syncs the given repos"
  [repos]
  (let [reposet (-> repos first kt/reposet :name)
        product (-> repos first kt/product :name)]
    (with-unique [manifest (download-original-manifest (not= product reposet))]
      (ui/create (assoc manifest :provider (-> repos first kt/provider)))
      (rh-repos/enable-disable-repos repos)
      (sync/perform-sync repos))))

(defn setup-org [envs repos]
  "Adds org to all the repos in the list, creates org and the envs
   chains"
  (let [org (-> envs first :org)
        repos (for [r repos]
                (update-in r [:reposet :product :provider] assoc :org org))]
    (ui/create org)
    (doseq [e (kt/chain envs)]
      (ui/create e))
    (prepare-org repos)))


(defn- upload-manifest
  "Uploads a subscription manifest from the filesystem local to the
   selenium browser. Optionally specify a new repository url for Red
   Hat content- if not specified, the default url is kept. Optionally
   specify whether to force the upload."
  [{:keys [file-path url provider]}]
  (nav/go-to ::subs/new-page provider)
  (when-not (browser isElementPresent ::subs/choose-file)
    (browser click ::subs/new))
  (when url
    (common/in-place-edit {::subs/repository-url-text url})
    (notification/success-type :prov-update))
  (sel/fill-ajax-form {::subs/choose-file file-path}
                       ::subs/upload-manifest)
  (browser refresh)
  ;;now the page seems to refresh on its own, but sometimes the ajax count
  ;; does not update. 
  ;; was using asynchronous notification until the bug https://bugzilla.redhat.com/show_bug.cgi?id=842325 gets fixed.
  (notification/check-for-success {:timeout-ms (* 10 60 1000) :match-pred (notification/request-type? :manifest-crud)}))

(defn refresh-manifest
  "Refreshes a subscription manifest uploaded"
  [manifest]
  (nav/go-to ::subs/new-page (kt/provider manifest))
  (browser click ::subs/refresh-manifest)
  (browser click ::ui/confirmation-yes))

(defn- delete-manifest
  "Deletes a subscription manifest uploaded"
  [manifest]
  (nav/go-to ::subs/new-page (kt/provider manifest))
  (browser click ::subs/delete-manifest)
  (browser click ::ui/confirmation-yes))

(defn upload-manifest-import-history?
  "Returns true if after an manifest import the history is updated."
  [ent]
  (nav/go-to ::subs/import-history-page ent)
  (browser isElementPresent ::subs/fetch-history-info))


(extend katello.Manifest
  ui/CRUD {:create upload-manifest
           :delete delete-manifest}
  
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
                               (repeatedly (fn [] (let [newpath (new-tmp-loc)]
                                                    (clone (:file-path m) newpath (@config :key-url))
                                                    (assoc m :file-path newpath)))))})
