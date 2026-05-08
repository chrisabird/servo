(ns user
  (:require [reloaded.repl :refer [go reset stop start set-init!]]
            [servo.config :as config]
            [servo.system :as system]))

(set-init! (fn [] (system/build-system (config/load-config :dev))))
