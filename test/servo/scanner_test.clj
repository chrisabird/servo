(ns servo.scanner-test
  (:require [clojure.test :refer [deftest testing is]]
            [servo.db :as db]
            [servo.scanner :as scanner])
  (:import (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)
           (java.util Comparator)))

(defn- ^Path temp-dir [prefix]
  (Files/createTempDirectory prefix (into-array FileAttribute [])))

(defn- ^Path mk-subdir [^Path parent name]
  (Files/createDirectory (.resolve parent ^String name)
                         (into-array FileAttribute [])))

(defn- ^Path mk-file [^Path parent name]
  (Files/createFile (.resolve parent ^String name)
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

(defn- collection-by-name [collections name]
  (->> collections vals (filter #(= name (:name %))) first))

(deftest fresh-scan-produces-collections
  (testing "two subdirs become two collections with correct fields"
    (let [root (temp-dir "servo-scan-root-")
          store-dir (temp-dir "servo-scan-store-")]
      (try
        (let [dir-a (mk-subdir root "alpha")
              dir-b (mk-subdir root "beta")
              _ (mk-file dir-a "one.stl")
              _ (mk-file dir-a "two.stl")
              nested (mk-subdir dir-b "inner")
              _ (mk-file nested "model.obj")
              store (test-store store-dir)
              collections (scanner/scan-root! (.toString root) store)
              by-name (into {} (map (juxt :name identity)) (vals collections))
              alpha (get by-name "alpha")
              beta (get by-name "beta")]
          (is (= 2 (count collections)))
          (is (= #{"alpha" "beta"} (set (keys by-name))))
          (is (= (.toString dir-a) (:folder-path alpha)))
          (is (= (.toString dir-b) (:folder-path beta)))
          (is (= [] (:tags alpha)))
          (is (= [] (:tags beta)))
          (is (= #{"one.stl" "two.stl"} (set (map :filename (:models alpha)))))
          (is (= #{"model.obj"} (set (map :filename (:models beta)))))
          (is (= collections (db/read-store store "collections.edn" {}))))
        (finally
          (delete-tree! root)
          (delete-tree! store-dir))))))

(deftest rescan-preserves-name-and-tags
  (testing "user-edited :name and :tags survive rescan; :models and :scanned-at refresh"
    (let [root (temp-dir "servo-scan-root-")
          store-dir (temp-dir "servo-scan-store-")]
      (try
        (let [dir-a (mk-subdir root "gadgets")
              file-old (mk-file dir-a "old.stl")
              store (test-store store-dir)
              first-scan (scanner/scan-root! (.toString root) store)
              [coll-id coll] (first first-scan)
              edited (assoc coll
                            :name "Custom Gadgets"
                            :tags ["mechanical" "wip"])
              _ (db/write-store! store "collections.edn" {coll-id edited})
              _ (Files/deleteIfExists file-old)
              _ (mk-file dir-a "new-a.stl")
              _ (mk-file dir-a "new-b.3mf")
              _ (Thread/sleep 5)
              second-scan (scanner/scan-root! (.toString root) store)
              refreshed (get second-scan coll-id)]
          (is (= 1 (count second-scan)))
          (is (= "Custom Gadgets" (:name refreshed)))
          (is (= ["mechanical" "wip"] (:tags refreshed)))
          (is (= #{"new-a.stl" "new-b.3mf"}
                 (set (map :filename (:models refreshed)))))
          (is (.after ^java.util.Date (:scanned-at refreshed)
                      ^java.util.Date (:scanned-at coll))))
        (finally
          (delete-tree! root)
          (delete-tree! store-dir))))))

(deftest rescan-removes-deleted-folder
  (testing "a collection whose folder no longer exists is dropped on rescan"
    (let [root (temp-dir "servo-scan-root-")
          store-dir (temp-dir "servo-scan-store-")]
      (try
        (let [dir-a (mk-subdir root "keepers")
              dir-b (mk-subdir root "doomed")
              _ (mk-file dir-a "a.stl")
              _ (mk-file dir-b "b.stl")
              store (test-store store-dir)
              _ (scanner/scan-root! (.toString root) store)
              _ (delete-tree! dir-b)
              second-scan (scanner/scan-root! (.toString root) store)]
          (is (= 1 (count second-scan)))
          (is (= "keepers" (:name (collection-by-name second-scan "keepers"))))
          (is (nil? (collection-by-name second-scan "doomed"))))
        (finally
          (delete-tree! root)
          (delete-tree! store-dir))))))

(deftest non-model-files-ignored
  (testing "only .stl/.3mf/.obj files appear in :models"
    (let [root (temp-dir "servo-scan-root-")
          store-dir (temp-dir "servo-scan-store-")]
      (try
        (let [dir-a (mk-subdir root "mixed")
              _ (mk-file dir-a "readme.txt")
              _ (mk-file dir-a "preview.png")
              _ (mk-file dir-a "part.stl")
              store (test-store store-dir)
              collections (scanner/scan-root! (.toString root) store)
              coll (collection-by-name collections "mixed")]
          (is (= 1 (count collections)))
          (is (= #{"part.stl"} (set (map :filename (:models coll))))))
        (finally
          (delete-tree! root)
          (delete-tree! store-dir))))))

(deftest files-in-root-ignored
  (testing "a model file directly in the root does not produce a collection"
    (let [root (temp-dir "servo-scan-root-")
          store-dir (temp-dir "servo-scan-store-")]
      (try
        (let [dir-a (mk-subdir root "real")
              _ (mk-file dir-a "inside.stl")
              _ (mk-file root "loose.stl")
              store (test-store store-dir)
              collections (scanner/scan-root! (.toString root) store)]
          (is (= 1 (count collections)))
          (is (= "real" (:name (first (vals collections)))))
          (is (= #{"inside.stl"}
                 (set (map :filename (:models (first (vals collections)))))))
          (is (every? #(not= "loose.stl" (:filename %))
                      (mapcat :models (vals collections)))))
        (finally
          (delete-tree! root)
          (delete-tree! store-dir))))))
