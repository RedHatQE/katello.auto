(ns ^{:author "jweiss"
      :doc "Contains functions for cloning Katello manifests,
            including changing the id and re-signing the new manifest."}
  katello.manifest
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [katello.tasks :refer [unique-format tmpfile]])
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


