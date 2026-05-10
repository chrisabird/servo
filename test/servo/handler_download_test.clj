(ns servo.handler-download-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [servo.db :as db]
            [servo.web.handler :as handler])
  (:import (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)
           (java.util Comparator UUID)
           (java.util.zip ZipInputStream)))

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

(defn- copy-fixture! [^Path dest-dir filename]
  (let [dest (.resolve dest-dir ^String filename)]
    (io/copy (io/file "test/fixtures/cube.stl") (.toFile dest))
    dest))

(defn- invoke [config store coll-id model-id]
  (let [h (#'handler/download-handler config store)]
    (h {:path-params {:collection-id coll-id :model-id model-id}})))

(deftest download-returns-file
  (testing "200 with attachment headers and File body when collection/model resolve inside STL_ROOT"
    (let [stl-root (temp-dir "servo-dl-root-")
          store-dir (temp-dir "servo-dl-store-")]
      (try
        (let [coll-dir (mk-subdir stl-root "widgets")
              model-path (.toFile (copy-fixture! coll-dir "cube.stl"))
              store (test-store store-dir)
              coll-id (str (UUID/randomUUID))
              model-id (str (UUID/randomUUID))
              collection {:id coll-id
                          :folder-path (.toString coll-dir)
                          :name "widgets"
                          :tags []
                          :models [{:id model-id
                                    :path (.getAbsolutePath model-path)
                                    :filename "cube.stl"}]}
              _ (db/write-store! store "collections.edn" {coll-id collection})
              config {:stl-root (.toString stl-root)}
              resp (invoke config store coll-id model-id)
              disp (get-in resp [:headers "content-disposition"])]
          (is (= 200 (:status resp)))
          (is (str/includes? disp "attachment"))
          (is (str/includes? disp "cube.stl"))
          (is (instance? java.io.File (:body resp))))
        (finally
          (delete-tree! stl-root)
          (delete-tree! store-dir))))))

(deftest download-not-found-collection
  (testing "404 when collection-id has no matching collection in the store"
    (let [stl-root (temp-dir "servo-dl-root-")
          store-dir (temp-dir "servo-dl-store-")]
      (try
        (let [store (test-store store-dir)
              config {:stl-root (.toString stl-root)}
              resp (invoke config store (str (UUID/randomUUID)) (str (UUID/randomUUID)))]
          (is (= 404 (:status resp))))
        (finally
          (delete-tree! stl-root)
          (delete-tree! store-dir))))))

(deftest download-not-found-model
  (testing "404 when collection exists but no model has the given model-id"
    (let [stl-root (temp-dir "servo-dl-root-")
          store-dir (temp-dir "servo-dl-store-")]
      (try
        (let [store (test-store store-dir)
              coll-id (str (UUID/randomUUID))
              collection {:id coll-id
                          :folder-path (.toString stl-root)
                          :name "empty"
                          :tags []
                          :models []}
              _ (db/write-store! store "collections.edn" {coll-id collection})
              config {:stl-root (.toString stl-root)}
              resp (invoke config store coll-id (str (UUID/randomUUID)))]
          (is (= 404 (:status resp))))
        (finally
          (delete-tree! stl-root)
          (delete-tree! store-dir))))))

(defn- zip-entry-names [^java.io.InputStream body]
  (let [zis (ZipInputStream. body)]
    (loop [entries []]
      (if-let [e (.getNextEntry zis)]
        (recur (conj entries (.getName e)))
        entries))))

(deftest collection-zip-download
  (testing "200 with zip body containing every model file in the collection"
    (let [stl-root (temp-dir "servo-zip-root-")
          store-dir (temp-dir "servo-zip-store-")]
      (try
        (let [coll-dir (mk-subdir stl-root "widgets")
              cube-path (.toFile (copy-fixture! coll-dir "cube.stl"))
              extra-path (.toFile (.resolve coll-dir "tiny.stl"))
              _ (spit extra-path "solid tiny\nendsolid tiny\n")
              store (test-store store-dir)
              coll-id (str (UUID/randomUUID))
              collection {:id coll-id
                          :folder-path (.toString coll-dir)
                          :name "Widgets Collection"
                          :tags []
                          :models [{:id (str (UUID/randomUUID))
                                    :path (.getAbsolutePath cube-path)
                                    :filename "cube.stl"}
                                   {:id (str (UUID/randomUUID))
                                    :path (.getAbsolutePath extra-path)
                                    :filename "tiny.stl"}]}
              _ (db/write-store! store "collections.edn" {coll-id collection})
              h (#'handler/collection-download-handler store)
              resp (h {:path-params {:id coll-id}})
              disp (get-in resp [:headers "content-disposition"])]
          (is (= 200 (:status resp)))
          (is (= "application/zip" (get-in resp [:headers "content-type"])))
          (is (str/includes? disp "attachment"))
          (is (= #{"cube.stl" "tiny.stl"} (set (zip-entry-names (:body resp))))))
        (finally
          (delete-tree! stl-root)
          (delete-tree! store-dir))))))

(deftest collection-zip-not-found
  (testing "404 when no collection has the given id"
    (let [store-dir (temp-dir "servo-zip-store-")]
      (try
        (let [store (test-store store-dir)
              h (#'handler/collection-download-handler store)
              resp (h {:path-params {:id (str (UUID/randomUUID))}})]
          (is (= 404 (:status resp))))
        (finally
          (delete-tree! store-dir))))))

(deftest download-path-traversal-blocked
  (testing "403 when the model path resolves outside STL_ROOT"
    (let [stl-root (temp-dir "servo-dl-root-")
          outside (temp-dir "servo-dl-outside-")
          store-dir (temp-dir "servo-dl-store-")]
      (try
        (let [store (test-store store-dir)
              coll-id (str (UUID/randomUUID))
              model-id (str (UUID/randomUUID))
              evil-path (.toString (.resolve outside "evil.stl"))
              collection {:id coll-id
                          :folder-path (.toString outside)
                          :name "evil"
                          :tags []
                          :models [{:id model-id
                                    :path evil-path
                                    :filename "evil.stl"}]}
              _ (db/write-store! store "collections.edn" {coll-id collection})
              config {:stl-root (.toString stl-root)}
              resp (invoke config store coll-id model-id)]
          (is (= 403 (:status resp))))
        (finally
          (delete-tree! stl-root)
          (delete-tree! outside)
          (delete-tree! store-dir))))))
