(ns servo.converter
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [taoensso.telemere :as log]))

(defn- glb-cache-file [store-dir collection-id model-id]
  (io/file store-dir "models" collection-id (str model-id ".glb")))

(defn- assimp-args [output-path model-path]
  ["assimp" "export" model-path output-path])

(defn- convert! [model-path output-path]
  (let [args                   (assimp-args output-path model-path)
        {:keys [exit out err]} (apply shell/sh args)
        glb-exists?            (.exists (io/file output-path))]
    (log/log! {:level :debug
               :msg "assimp glb result"
               :data {:cmd args :exit exit :out out :err err :glb-exists glb-exists?}})
    (if (and (zero? exit) glb-exists?)
      output-path
      (do
        (log/log! {:level :error
                   :msg "assimp glb conversion failed"
                   :data {:cmd args :exit exit :out out :err err :glb-exists glb-exists?}})
        nil))))

(defn ensure-glb! [store collection-id model-id model-path]
  (let [cache-file  (glb-cache-file (:dir store) collection-id model-id)
        output-path (.getAbsolutePath cache-file)]
    (if (.exists cache-file)
      output-path
      (do
        (io/make-parents cache-file)
        (convert! model-path output-path)))))
