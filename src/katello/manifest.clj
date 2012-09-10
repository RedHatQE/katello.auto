(ns katello.manifest
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [com.redhat.qe.auto.selenium.selenium :refer [browser]]
            (katello [conf :refer [config]]
                     [tasks :refer [tmpfile unique-format]]
                     [ui-tasks :refer [navigate in-place-edit fill-ajax-form]]
                     [notifications :refer [check-for-success]]))
  (:import [java.util.zip ZipEntry ZipFile ZipOutputStream ZipInputStream]
           [java.io ByteArrayInputStream ByteArrayOutputStream]))

(defn new-zip-inputstream [old-zis]
  (let [baos (ByteArrayOutputStream.)]
    (io/copy old-zis baos)
    (ZipInputStream. (ByteArrayInputStream. (.toByteArray baos)))))

(defn update-in-nested-zip
  "Takes a zip inputstream and returns an inputstream with all the
  files in replacements replaced with their contents."
  [zis path content]
  (if (empty? path) zis
      (let [baos (ByteArrayOutputStream.)]
        (with-open [dzos (ZipOutputStream. baos)]
          (loop [this-entry (.getNextEntry zis)]
            (when this-entry
              (.putNextEntry dzos (ZipEntry. (.getName this-entry)))
              (if (= (first path)
                     (.getName this-entry))
                (if (= (count path) 1)
                  (io/copy content dzos)

                  ;;nested zip
                  (with-open [this-entry-is (new-zip-inputstream zis)]
                    (io/copy (update-in-nested-zip this-entry-is (pop path) content)
                             dzos)))
                ;;non-matching file
                (io/copy zis dzos))
              
              (recur (.getNextEntry zis)))))
        (ByteArrayInputStream. (.toByteArray baos)))))

(defn new-tmp-loc
  "Returns a unique file path in the system tmp dir, ending in .zip"
  []
  (-> "manifest-%s.zip" unique-format tmpfile))

(defn clone
  "Takes a manifest file location, copies it,and updates it internally
   so that it will be accepted by katello as a new manifest. Also
   specify file output location for the clone (full path string)"
  [source-path dest-path]
  (io/copy
   (update-in-nested-zip
    (-> source-path java.io.FileInputStream. ZipInputStream.) 
    '("consumer_export.zip" "export/consumer.json")
    (->> (java.util.UUID/randomUUID) .toString (hash-map :uuid) json/json-str))
   (java.io.File. dest-path)))

(defn upload
  "Uploads a subscription manifest from the filesystem local to the
   selenium browser. Optionally specify a new repository url for Red
   Hat content- if not specified, the default url is kept. Optionally
   specify whether to force the upload."
  [file-path & [{:keys [repository-url]}]]
  (navigate :redhat-subscriptions-page)
  (when-not (browser isElementPresent :choose-file)
    (browser click :import-manifest))
  (when repository-url
    (in-place-edit {:redhat-provider-repository-url-text repository-url}))
  (fill-ajax-form {:choose-file file-path}
                  :upload)
  (check-for-success {:timeout-ms 120000}))
  ;;now the page seems to refresh on its own, but sometimes the ajax count
  ;; does not update. 
  ;; was using asynchronous notification until the bug https://bugzilla.redhat.com/show_bug.cgi?id=842325 gets fixed.
  ;(check-for-success))

(defn already-uploaded?
  "Returns true if the current organization already has Red Hat
  content uploaded."
  []
  (navigate :redhat-repositories-page)
  (browser isElementPresent :subscriptions-items))

(defn upload-new-cloned
  "Clones the manifest at orig-file-path and uploads it to the current org."
  [orig-file-path & [{:keys [repository-url] :as m}]]
  (let [clone-loc (new-tmp-loc)]
    (clone orig-file-path clone-loc)
    (upload clone-loc m)))