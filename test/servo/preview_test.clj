(ns servo.preview-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest testing is]]
            [servo.db :as db]
            [servo.preview :as preview]
            [servo.scanner :as scanner])
  (:import (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)
           (java.util Comparator UUID)))

(defn- ^Path temp-dir [prefix]
  (Files/createTempDirectory prefix (into-array FileAttribute [])))

(defn- ^Path mk-subdir [^Path parent name]
  (Files/createDirectory (.resolve parent ^String name)
                         (into-array FileAttribute [])))

(defn- delete-tree! [^Path root]
  (when (Files/exists root (into-array java.nio.file.LinkOption []))
    (with-open [stream (Files/walk root (into-array java.nio.file.FileVisitOption []))]
      (doseq [^Path p (-> stream
                          (.sorted (Comparator/reverseOrder))
                          .iterator
                          iterator-seq)]
        (Files/deleteIfExists p)))))

(defn- test-store [^Path dir]
  (db/map->Store {:dir (.toString dir)}))

(defn- write-default-patterns! [store]
  (db/write-store! store "patterns.edn" [{:id "p1" :name "Top" :pattern "{name}"}]))

(defn- copy-fixture! [^Path dest-dir filename]
  (let [dest (.resolve dest-dir ^String filename)]
    (io/copy (io/file "test/fixtures/cube.stl") (.toFile dest))
    dest))

(defn- collection-by-name [collections name]
  (->> collections vals (filter #(= name (:name %))) first))

(deftest preview-generated-from-real-stl
  (testing "scan+generate produces a PNG on disk and records :preview-path"
    (let [root (temp-dir "servo-preview-root-")
          store-dir (temp-dir "servo-preview-store-")]
      (try
        (let [coll-dir (mk-subdir root "widgets")
              _ (copy-fixture! coll-dir "cube.stl")
              store (test-store store-dir)
              _ (write-default-patterns! store)
              collections (scanner/scan-root! (.toString root) store)
              coll (collection-by-name collections "widgets")
              model (first (:models coll))
              expected-png (io/file (.toString store-dir) "previews" (:id coll) (str (:id model) ".png"))]
          (is (.exists expected-png))
          (is (= (.getAbsolutePath expected-png) (:preview-path model))))
        (finally
          (delete-tree! root)
          (delete-tree! store-dir))))))

(deftest existing-preview-not-regenerated
  (testing "a second scan does not re-run f3d when the PNG already exists"
    (let [root (temp-dir "servo-preview-root-")
          store-dir (temp-dir "servo-preview-store-")]
      (try
        (let [coll-dir (mk-subdir root "widgets")
              _ (copy-fixture! coll-dir "cube.stl")
              store (test-store store-dir)
              _ (write-default-patterns! store)
              collections (scanner/scan-root! (.toString root) store)
              coll (collection-by-name collections "widgets")
              model (first (:models coll))
              png (io/file (.toString store-dir) "previews" (:id coll) (str (:id model) ".png"))
              first-modified (.lastModified png)]
          (is (.exists png))
          (Thread/sleep 1100)
          (scanner/scan-root! (.toString root) store)
          (is (= first-modified (.lastModified png))))
        (finally
          (delete-tree! root)
          (delete-tree! store-dir))))))

(deftest failed-render-records-nil-preview
  (testing "an invalid STL leaves :preview-path nil and produces no PNG"
    (let [root (temp-dir "servo-preview-root-")
          store-dir (temp-dir "servo-preview-store-")]
      (try
        (let [coll-dir (mk-subdir root "broken")
              missing-path (.getAbsolutePath (.toFile (.resolve coll-dir "missing.stl")))
              store (test-store store-dir)
              coll-id (str (UUID/randomUUID))
              model-id (str (UUID/randomUUID))
              collection {:id coll-id
                          :folder-path (.toString coll-dir)
                          :name "broken"
                          :tags []
                          :models [{:id model-id
                                    :path missing-path
                                    :filename "missing.stl"}]}
              _ (db/write-store! store "collections.edn" {coll-id collection})
              updated (preview/generate-previews! store)
              model (-> updated (get coll-id) :models first)
              expected-png (io/file (.toString store-dir) "previews" coll-id (str model-id ".png"))]
          (is (nil? (:preview-path model)))
          (is (not (.exists expected-png))))
        (finally
          (delete-tree! root)
          (delete-tree! store-dir))))))
