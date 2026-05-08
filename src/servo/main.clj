(ns servo.main
  (:require [com.stuartsierra.component :as component]
            [servo.config :as config]
            [servo.system :as system]
            [taoensso.telemere :as log])
  (:gen-class))

(defn -main [& _]
  (let [cfg    (config/load-config)
        system (component/start (system/build-system cfg))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable
                               (fn []
                                 (log/log! {:level :info :msg "shutdown hook"})
                                 (component/stop system))))
    (log/log! {:level :info :msg "servo started" :data {:env (:env cfg)}})
    @(promise)))
