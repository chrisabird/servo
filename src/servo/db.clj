(ns servo.db
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [com.stuartsierra.component :as component]
            [taoensso.telemere :as log]))

(defrecord Store [dir]
  component/Lifecycle
  (start [this]
    (log/log! {:level :info :msg "starting edn store" :data {:dir dir}})
    (.mkdirs (io/file dir))
    this)
  (stop [this]
    (log/log! {:level :info :msg "stopping edn store"})
    this))

(defn read-store
  "Returns the parsed EDN value for `filename` in the store, or `default` if absent."
  ([store filename] (read-store store filename nil))
  ([{:keys [dir]} filename default]
   (let [f (io/file dir filename)]
     (if (.exists f)
       (edn/read-string (slurp f))
       default))))

(defn write-store!
  "Writes `value` as EDN to `filename` in the store."
  [{:keys [dir]} filename value]
  (let [f (io/file dir filename)]
    (io/make-parents f)
    (spit f (pr-str value))))

(defn new-store [config]
  (map->Store {:dir (get-in config [:db :dir])}))
