(ns servo.scan
  (:require [servo.db :as db]
            [servo.scanner :as scanner]))

(def ^:private state (atom {:status :idle}))

(defn get-state [] @state)

(defn- count-previews [collections]
  (count (filter :preview-path (mapcat :models (vals collections)))))

(defn- failed-models [collections]
  (->> (vals collections)
       (mapcat :models)
       (filter #(nil? (:preview-path %)))
       (map :filename)
       vec))

(defn trigger! [root-path store]
  (when-not (= :running (:status @state))
    (let [before-collections (db/read-store store "collections.edn" {})
          before-previews    (count-previews before-collections)
          on-progress        (fn [update] (swap! state merge update))]
      (reset! state {:status :running :message "Starting scan..."})
      (future
        (try
          (let [after-collections (scanner/scan-root! root-path store :on-progress on-progress)
                new-previews      (max 0 (- (count-previews after-collections)
                                            before-previews))]
            (reset! state {:status             :complete
                           :collections-found  (count after-collections)
                           :previews-generated new-previews
                           :errors             (failed-models after-collections)}))
          (catch Exception e
            (reset! state {:status :error :message (.getMessage e)})))))))
