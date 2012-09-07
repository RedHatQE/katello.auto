(ns katello.manifest
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json])
  (:import [java.util.zip ZipEntry ZipFile ZipOutputStream ZipInputStream]
           [java.io ByteArrayInputStream ByteArrayOutputStream]))

(defn copy-n-bytes [is os n]
  (let [buffer (make-array Byte/TYPE 1024)]
    (loop [remaining n]
      (let [size (.read is buffer 0 (Math/min (count buffer) remaining))]
        (when (pos? size)
          (do (.write os buffer 0 size)
              (recur (- n size))))))))

(defn new-zip-inputstream [old-zis entry]
  (let [baos (ByteArrayOutputStream.)]
    (copy-n-bytes old-zis baos (.getSize entry))
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
                  (with-open [this-entry-is (new-zip-inputstream zis this-entry)]
                    (io/copy (update-in-nested-zip this-entry-is (pop path) content)
                             dzos)))
                ;;non-matching file
                (copy-n-bytes zis dzos (.getSize this-entry)))
              
              (recur (.getNextEntry zis)))))
        (ByteArrayInputStream. (.toByteArray baos)))))

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