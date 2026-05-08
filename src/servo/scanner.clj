(ns servo.scanner
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [servo.db :as db]
            [servo.pattern :as pattern]
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

(defn- subdirs-to-depth
  "Returns a lazy seq of every descendant directory of `root`, up to `max-depth`
  levels deep. Excludes `root` itself."
  [^File root max-depth]
  (letfn [(step [^File dir d]
            (when (< d max-depth)
              (let [children (->> (.listFiles dir)
                                  (filter (fn [^File f] (.isDirectory f))))]
                (lazy-cat children (mapcat #(step % (inc d)) children)))))]
    (step root 0)))

(defn- path-segments
  "Returns the segments of `dir` relative to `root` as a vector of strings."
  [^File root ^File dir]
  (let [rel (.relativize (.toPath root) (.toPath dir))]
    (mapv str (iterator-seq (.iterator rel)))))

(defn- first-match
  "Returns [pattern-name captures] for the first named pattern whose length
  equals segments and whose match succeeds, or nil if none match.
  named-patterns is a seq of [name parsed-pattern]."
  [named-patterns segments]
  (some (fn [[pname parsed]]
          (when (= (count parsed) (count segments))
            (when-let [captures (pattern/match parsed segments)]
              [pname captures])))
        named-patterns))

(defn- build-collection [existing-by-path ^File dir segments pattern-name captures]
  (let [folder-path   (.getAbsolutePath dir)
        existing      (get existing-by-path folder-path)
        derived       (cons pattern-name (pattern/derive-tags captures))
        existing-tags (or (:tags existing) [])
        merged-tags   (vec (distinct (concat derived existing-tags)))]
    {:id          (or (:id existing) (str (UUID/randomUUID)))
     :folder-path folder-path
     :name        (pattern/derive-name segments)
     :tags        merged-tags
     :models      (find-models dir)
     :scanned-at  (Date.)}))

(defn scan-root! [root-path store & {:keys [on-progress] :or {on-progress (constantly nil)}}]
  (let [_ (on-progress {:message "Discovering collections..."})
        patterns (db/read-store store "patterns.edn" [])
        named-patterns (mapv (fn [p] [(:name p) (pattern/parse-pattern (:pattern p))]) patterns)]
    (if (empty? named-patterns)
      (do
        (db/write-store! store "collections.edn" {})
        {})
      (let [root (io/file root-path)
            max-depth (apply max (map #(count (second %)) named-patterns))
            existing (db/read-store store "collections.edn" {})
            existing-by-path (into {} (map (juxt :folder-path identity)) (vals existing))
            collections (->> (subdirs-to-depth root max-depth)
                             (keep (fn [^File dir]
                                     (let [segs (path-segments root dir)]
                                       (when-let [[pname captures] (first-match named-patterns segs)]
                                         (build-collection existing-by-path dir segs pname captures)))))
                             (reduce (fn [acc c] (assoc acc (:id c) c)) {}))]
        (db/write-store! store "collections.edn" collections)
        (on-progress {:message (str "Found " (count collections) " collections, generating previews...")})
        (preview/generate-previews! store :on-progress on-progress)))))
