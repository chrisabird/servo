(ns servo.preview
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [servo.db :as db]
            [taoensso.telemere :as log]))

(defn- preview-path-for [store-dir collection-id model-id]
  (-> (io/file store-dir "previews" collection-id (str model-id ".png"))
      .getAbsolutePath))

(defn- ensure-preview-dir! [store-dir collection-id]
  (.mkdirs (io/file store-dir "previews" collection-id)))

(defn- f3d-args [output-path model-path]
  ["f3d" "--output" output-path model-path])

(defn- render-preview! [model-path output-path]
  (let [args                    (f3d-args output-path model-path)
        {:keys [exit out err]}  (apply shell/sh args)
        png-exists?             (.exists (io/file output-path))]
    (log/log! {:level :debug
               :msg "f3d result"
               :data {:cmd args :exit exit :out out :err err :png-exists png-exists?}})
    (if (and (zero? exit) png-exists?)
      output-path
      (do
        (log/log! {:level :error
                   :msg "f3d failed"
                   :data {:cmd args :exit exit :out out :err err :png-exists png-exists?}})
        nil))))

(defn- process-model [store-dir collection-id {:keys [id path] :as model}]
  (let [output-path (preview-path-for store-dir collection-id id)]
    (if (.exists (io/file output-path))
      (assoc model :preview-path output-path)
      (do
        (log/log! {:level :info
                   :msg "rendering preview"
                   :data {:model path :output output-path}})
        (assoc model :preview-path (render-preview! path output-path))))))

(defn- process-collection [store-dir {:keys [id models] :as collection} on-each]
  (ensure-preview-dir! store-dir id)
  (assoc collection :models (mapv #(on-each (process-model store-dir id %) %) models)))

(defn generate-previews! [store & {:keys [on-progress] :or {on-progress (constantly nil)}}]
  (let [{:keys [dir]} store
        collections (db/read-store store "collections.edn" {})
        total       (->> collections vals (mapcat :models) count)
        counter     (volatile! 0)
        updated     (update-vals
                     collections
                     (fn [{:keys [name] :as collection}]
                       (process-collection
                        dir
                        collection
                        (fn [processed {:keys [filename]}]
                          (let [n (vswap! counter inc)]
                            (on-progress {:message  (str "Rendering " filename " (" name ")")
                                          :progress {:done n :total total}})
                            processed)))))]
    (db/write-store! store "collections.edn" updated)
    updated))
