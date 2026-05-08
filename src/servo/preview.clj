(ns servo.preview
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [servo.db :as db]
            [taoensso.telemere :as log]))

(defn- preview-path-for [folder-path filename]
  (-> (io/file folder-path ".servo-images" (str filename ".png"))
      .getAbsolutePath))

(defn- ensure-preview-dir! [folder-path]
  (.mkdirs (io/file folder-path ".servo-images")))

(defn- render-preview! [model-path output-path]
  (let [{:keys [exit err]} (shell/sh "f3d" "--rendering-backend" "osmesa" "--output" output-path model-path)]
    (if (zero? exit)
      output-path
      (do
        (log/log! {:level :error
                   :msg "f3d failed"
                   :data {:model model-path :exit exit :err err}})
        nil))))

(defn- process-model [folder-path {:keys [path filename] :as model}]
  (let [output-path (preview-path-for folder-path filename)]
    (if (.exists (io/file output-path))
      (assoc model :preview-path output-path)
      (do
        (log/log! {:level :info
                   :msg "rendering preview"
                   :data {:model path :output output-path}})
        (assoc model :preview-path (render-preview! path output-path))))))

(defn- process-collection [{:keys [folder-path models] :as collection} on-each]
  (ensure-preview-dir! folder-path)
  (assoc collection :models (mapv #(on-each (process-model folder-path %) %) models)))

(defn generate-previews! [store & {:keys [on-progress] :or {on-progress (constantly nil)}}]
  (let [collections (db/read-store store "collections.edn" {})
        total       (->> collections vals (mapcat :models) count)
        counter     (volatile! 0)
        updated     (update-vals
                     collections
                     (fn [{:keys [name] :as collection}]
                       (process-collection
                        collection
                        (fn [processed {:keys [filename]}]
                          (let [n (vswap! counter inc)]
                            (on-progress {:message  (str "Rendering " filename " (" name ")")
                                          :progress {:done n :total total}})
                            processed)))))]
    (db/write-store! store "collections.edn" updated)
    updated))
