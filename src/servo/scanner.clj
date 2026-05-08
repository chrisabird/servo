(ns servo.scanner
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [servo.db :as db]
            [servo.preview :as preview])
  (:import (java.io File)
           (java.util Date UUID)))

(def ^:private model-extensions #{"stl" "3mf" "obj"})

(defn- file-extension [^File f]
  (let [n (.getName f)
        i (.lastIndexOf n ".")]
    (when (pos? i)
      (-> n (subs (inc i)) str/lower-case))))

(defn- model-file? [^File f]
  (and (.isFile f)
       (contains? model-extensions (file-extension f))))

(defn- find-models [^File dir]
  (->> (file-seq dir)
       (filter model-file?)
       (map (fn [^File f]
              {:path (.getAbsolutePath f)
               :filename (.getName f)}))
       vec))

(defn- immediate-subdirs [^File root]
  (->> (.listFiles root)
       (filter (fn [^File f] (.isDirectory f)))))

(defn- new-collection [^File dir]
  {:id (str (UUID/randomUUID))
   :folder-path (.getAbsolutePath dir)
   :name (.getName dir)
   :tags []
   :models (find-models dir)
   :scanned-at (Date.)})

(defn- refresh-collection [existing ^File dir]
  (assoc existing
         :models (find-models dir)
         :scanned-at (Date.)))

(defn- collection-for [existing-by-path ^File dir]
  (if-let [existing (get existing-by-path (.getAbsolutePath dir))]
    (refresh-collection existing dir)
    (new-collection dir)))

(defn scan-root! [root-path store & {:keys [on-progress] :or {on-progress (constantly nil)}}]
  (let [root (io/file root-path)
        _ (on-progress {:message "Discovering collections..."})
        existing (db/read-store store "collections.edn" {})
        existing-by-path (into {} (map (juxt :folder-path identity)) (vals existing))
        collections (->> (immediate-subdirs root)
                         (map #(collection-for existing-by-path %))
                         (reduce (fn [acc c] (assoc acc (:id c) c)) {}))]
    (db/write-store! store "collections.edn" collections)
    (on-progress {:message (str "Found " (count collections) " collections, generating previews...")})
    (preview/generate-previews! store :on-progress on-progress)))
