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

(defn- write-patterns! [store patterns]
  (db/write-store! store "patterns.edn" patterns))

(defn- collection-by-name [collections name]
  (->> collections vals (filter #(= name (:name %))) first))

(deftest fresh-scan-produces-collections
  (testing "subdirs matching pattern become collections with derived fields"
    (let [root (temp-dir "servo-scan-root-")
          store-dir (temp-dir "servo-scan-store-")]
      (try
        (let [store (test-store store-dir)
              _ (write-patterns! store [{:id "p1" :name "Game" :pattern "game/{name}"}])
              game (mk-subdir root "game")
              dir-a (mk-subdir game "alpha")
              dir-b (mk-subdir game "beta")
              _ (mk-file dir-a "one.stl")
              nested (mk-subdir dir-b "inner")
              _ (mk-file nested "model.obj")
              collections (scanner/scan-root! (.toString root) store)
              alpha (collection-by-name collections "game / alpha")
              beta (collection-by-name collections "game / beta")]
          (is (= 2 (count collections)))
          (is (= (.toString dir-a) (:folder-path alpha)))
          (is (= (.toString dir-b) (:folder-path beta)))
          (is (= ["Game" "alpha"] (:tags alpha)))
          (is (= ["Game" "beta"] (:tags beta)))
          (is (= "game / alpha" (:name alpha)))
          (is (= "game / beta" (:name beta)))
          (is (= #{"one.stl"} (set (map :filename (:models alpha)))))
          (is (= #{"model.obj"} (set (map :filename (:models beta)))))
          (is (= collections (db/read-store store "collections.edn" {}))))
        (finally
          (delete-tree! root)
          (delete-tree! store-dir))))))

(deftest rescan-preserves-manual-tags
  (testing "user-edited :tags survive rescan; pattern-derived tags re-merged; :models refresh"
    (let [root (temp-dir "servo-scan-root-")
          store-dir (temp-dir "servo-scan-store-")]
      (try
        (let [store (test-store store-dir)
              _ (write-patterns! store [{:id "p1" :name "Top" :pattern "{name}"}])
              dir-a (mk-subdir root "gadgets")
              file-old (mk-file dir-a "old.stl")
              first-scan (scanner/scan-root! (.toString root) store)
              [coll-id coll] (first first-scan)
              edited (assoc coll :tags ["mechanical" "wip"])
              _ (db/write-store! store "collections.edn" {coll-id edited})
              _ (Files/deleteIfExists file-old)
              _ (mk-file dir-a "new-a.stl")
              _ (mk-file dir-a "new-b.3mf")
              _ (Thread/sleep 5)
              second-scan (scanner/scan-root! (.toString root) store)
              refreshed (get second-scan coll-id)]
          (is (= 1 (count second-scan)))
          (is (= "gadgets" (:name refreshed)))
          ;; pattern name first, then captured tag, then preserved manual tags
          (is (= ["Top" "gadgets" "mechanical" "wip"] (:tags refreshed)))
          (is (= #{"new-a.stl" "new-b.3mf"}
                 (set (map :filename (:models refreshed)))))
          (is (.after ^java.util.Date (:scanned-at refreshed)
                      ^java.util.Date (:scanned-at coll))))
        (finally
          (delete-tree! root)
          (delete-tree! store-dir))))))

(deftest rescan-removes-unmatched-collection
  (testing "a collection whose folder no longer exists is dropped on rescan"
    (let [root (temp-dir "servo-scan-root-")
          store-dir (temp-dir "servo-scan-store-")]
      (try
        (let [store (test-store store-dir)
              _ (write-patterns! store [{:id "p1" :name "Top" :pattern "{name}"}])
              dir-a (mk-subdir root "keepers")
              dir-b (mk-subdir root "doomed")
              _ (mk-file dir-a "a.stl")
              _ (mk-file dir-b "b.stl")
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
        (let [store (test-store store-dir)
              _ (write-patterns! store [{:id "p1" :name "Top" :pattern "{name}"}])
              dir-a (mk-subdir root "mixed")
              _ (mk-file dir-a "readme.txt")
              _ (mk-file dir-a "preview.png")
              _ (mk-file dir-a "part.stl")
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
        (let [store (test-store store-dir)
              _ (write-patterns! store [{:id "p1" :name "Top" :pattern "{name}"}])
              dir-a (mk-subdir root "real")
              _ (mk-file dir-a "inside.stl")
              _ (mk-file root "loose.stl")
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

(deftest zero-patterns-produces-no-collections
  (testing "with no patterns, scan returns {} and writes {} to store"
    (let [root (temp-dir "servo-scan-root-")
          store-dir (temp-dir "servo-scan-store-")]
      (try
        (let [store (test-store store-dir)
              _ (write-patterns! store [])
              dir-a (mk-subdir root "lonely")
              _ (mk-file dir-a "thing.stl")
              collections (scanner/scan-root! (.toString root) store)]
          (is (= {} collections))
          (is (= {} (db/read-store store "collections.edn" :missing))))
        (finally
          (delete-tree! root)
          (delete-tree! store-dir))))))

(deftest multiple-patterns-match-different-subtrees
  (testing "two patterns each match their own subtree"
    (let [root (temp-dir "servo-scan-root-")
          store-dir (temp-dir "servo-scan-store-")]
      (try
        (let [store (test-store store-dir)
              _ (write-patterns! store [{:id "p1" :name "Game" :pattern "game/{unit}"}
                                        {:id "p2" :name "Terrain" :pattern "terrain/{type}"}])
              game (mk-subdir root "game")
              terrain (mk-subdir root "terrain")
              marines (mk-subdir game "marines")
              forest (mk-subdir terrain "forest")
              _ (mk-file marines "m.stl")
              _ (mk-file forest "f.stl")
              collections (scanner/scan-root! (.toString root) store)
              g (collection-by-name collections "game / marines")
              t (collection-by-name collections "terrain / forest")]
          (is (= 2 (count collections)))
          (is (= ["Game" "marines"] (:tags g)))
          (is (= ["Terrain" "forest"] (:tags t)))
          (is (= "game / marines" (:name g)))
          (is (= "terrain / forest" (:name t))))
        (finally
          (delete-tree! root)
          (delete-tree! store-dir))))))

(deftest first-match-wins
  (testing "when two patterns of equal length match, first wins"
    (let [root (temp-dir "servo-scan-root-")
          store-dir (temp-dir "servo-scan-store-")]
      (try
        (let [store (test-store store-dir)
              ;; Both patterns would match 40k/<anything> — write directly,
              ;; bypassing UI conflict validation.
              _ (write-patterns! store [{:id "p1" :name "Unit" :pattern "40k/{unit}"}
                                        {:id "p2" :name "Creator" :pattern "40k/{creator}"}])
              top (mk-subdir root "40k")
              marines (mk-subdir top "marines")
              _ (mk-file marines "m.stl")
              collections (scanner/scan-root! (.toString root) store)
              coll (first (vals collections))]
          (is (= 1 (count collections)))
          (is (= ["Unit" "marines"] (:tags coll)))
          (is (= "40k / marines" (:name coll))))
        (finally
          (delete-tree! root)
          (delete-tree! store-dir))))))

(defn- model-ids-by-filename [collections]
  (->> collections
       vals
       (mapcat :models)
       (map (juxt :filename :id))
       (into {})))

(deftest model-uuids-stable-across-rescans
  (testing "rescanning the same dir yields identical model :id UUIDs"
    (let [root (temp-dir "servo-scan-root-")
          store-dir (temp-dir "servo-scan-store-")]
      (try
        (let [store (test-store store-dir)
              _ (write-patterns! store [{:id "p1" :name "Top" :pattern "{name}"}])
              dir-a (mk-subdir root "things")
              _ (mk-file dir-a "a.stl")
              _ (mk-file dir-a "b.3mf")
              first-scan (scanner/scan-root! (.toString root) store)
              first-ids (model-ids-by-filename first-scan)
              second-scan (scanner/scan-root! (.toString root) store)
              second-ids (model-ids-by-filename second-scan)]
          (is (every? some? (vals first-ids)))
          (is (every? string? (vals first-ids)))
          (is (= #{"a.stl" "b.3mf"} (set (keys first-ids))))
          (is (= first-ids second-ids))
          (let [persisted (db/read-store store "model-ids.edn" :missing)]
            (is (map? persisted))
            (is (= (set (vals first-ids)) (set (vals persisted))))))
        (finally
          (delete-tree! root)
          (delete-tree! store-dir))))))

(deftest new-model-gets-new-uuid-existing-preserved
  (testing "adding a file yields a fresh UUID without disturbing existing ones"
    (let [root (temp-dir "servo-scan-root-")
          store-dir (temp-dir "servo-scan-store-")]
      (try
        (let [store (test-store store-dir)
              _ (write-patterns! store [{:id "p1" :name "Top" :pattern "{name}"}])
              dir-a (mk-subdir root "things")
              _ (mk-file dir-a "a.stl")
              first-scan (scanner/scan-root! (.toString root) store)
              first-ids (model-ids-by-filename first-scan)
              _ (mk-file dir-a "b.stl")
              second-scan (scanner/scan-root! (.toString root) store)
              second-ids (model-ids-by-filename second-scan)]
          (is (= #{"a.stl"} (set (keys first-ids))))
          (is (= #{"a.stl" "b.stl"} (set (keys second-ids))))
          (is (= (get first-ids "a.stl") (get second-ids "a.stl")))
          (is (some? (get second-ids "b.stl")))
          (is (not= (get second-ids "a.stl") (get second-ids "b.stl"))))
        (finally
          (delete-tree! root)
          (delete-tree! store-dir))))))
