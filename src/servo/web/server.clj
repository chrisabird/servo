(ns servo.web.server
  (:require [com.stuartsierra.component :as component]
            [ring.adapter.jetty :as jetty]
            [taoensso.telemere :as log]))

(defrecord WebServer [config handler server]
  component/Lifecycle
  (start [this]
    (if server
      this
      (let [port (get-in config [:server :port])]
        (log/log! {:level :info :msg "starting web server" :data {:port port}})
        (assoc this :server
               (jetty/run-jetty handler
                                {:port             port
                                 :join?            false
                                 :virtual-threads? true})))))
  (stop [this]
    (when server
      (log/log! {:level :info :msg "stopping web server"})
      (.stop server))
    (assoc this :server nil)))

(defn new-web-server [config]
  (map->WebServer {:config config}))
