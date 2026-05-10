(ns servo.converter-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest testing is]]
            [servo.converter :as converter]
            [servo.db :as db])
  (:import (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)
           (java.util Comparator UUID)))

(defn- ^Path temp-dir [prefix]
  (Files/createTempDirectory prefix (into-array FileAttribute [])))

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

(deftest glb-cache-hit-skips-conversion
  (testing "ensure-glb! returns the cached path without invoking f3d when the glb already exists"
    (let [store-dir (temp-dir "servo-converter-store-")]
      (try
        (let [store         (test-store store-dir)
              coll-id       (str (UUID/randomUUID))
              model-id      (str (UUID/randomUUID))
              cached-glb    (io/file (.toString store-dir) "models" coll-id (str model-id ".glb"))
              dummy-content "pre-existing-glb-bytes"
              missing-model (.getAbsolutePath (io/file (.toString store-dir) "does-not-exist.stl"))]
          (io/make-parents cached-glb)
          (spit cached-glb dummy-content)
          (let [result (converter/ensure-glb! store coll-id model-id missing-model)]
            (is (= (.getAbsolutePath cached-glb) result))
            (is (= dummy-content (slurp cached-glb)))))
        (finally
          (delete-tree! store-dir))))))

(defn- glb-magic? [path]
  (let [buf (byte-array 4)]
    (with-open [s (java.io.FileInputStream. path)]
      (.read s buf))
    (= "glTF" (String. buf java.nio.charset.StandardCharsets/US_ASCII))))

(deftest glb-cache-miss-converts-file
  (testing "ensure-glb! invokes assimp on a real STL and writes a valid GLB to the cache"
    (let [store-dir (temp-dir "servo-converter-store-")]
      (try
        (let [store      (test-store store-dir)
              coll-id    (str (UUID/randomUUID))
              model-id   (str (UUID/randomUUID))
              model-path (.getAbsolutePath (io/file "test/fixtures/cube.stl"))
              result     (converter/ensure-glb! store coll-id model-id model-path)]
          (is (string? result))
          (is (.exists (io/file result)))
          (is (glb-magic? result) "output must be a valid GLB (magic bytes glTF)"))
        (finally
          (delete-tree! store-dir))))))
