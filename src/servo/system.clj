(ns servo.system
  (:require [com.stuartsierra.component :as component]
            [servo.db :as db]
            [servo.web.handler :as handler]
            [servo.web.server :as server]))

(defn build-system [config]
  (component/system-map
   :db      (db/new-store config)
   :handler (component/using (handler/new-handler config) [:db])
   :server  (component/using (server/new-web-server config) [:handler])))
