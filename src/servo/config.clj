(ns servo.config
  (:require [aero.core :as aero]
            [clojure.java.io :as io]))

(defn- env-profile []
  (keyword (or (System/getenv "SERVO_ENV") "dev")))

(defn load-config
  ([] (load-config (env-profile)))
  ([profile]
   (aero/read-config (io/resource "config.edn") {:profile profile})))
