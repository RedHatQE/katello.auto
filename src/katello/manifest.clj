(ns katello.manifest
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [katello.tasks :refer [unique-format tmpfile]])
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

