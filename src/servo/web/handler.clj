(ns servo.web.handler
  (:require [hiccup2.core :as h]
            [jsonista.core :as json]
            [reitit.ring :as ring]))

(defn- layout [title & body]
  (str
   "<!DOCTYPE html>"
   (h/html
    [:html {:lang "en"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:title title]
      [:link {:rel "stylesheet" :href "/css/output.css"}]
      [:script {:src "https://unpkg.com/htmx.org@2.0.4" :defer true}]
      [:script {:src "https://unpkg.com/alpinejs@3.14.7/dist/cdn.min.js" :defer true}]]
     [:body {:class "min-h-screen bg-base-100 text-base-content"}
      (into [:main {:class "container mx-auto p-8"}] body)]])))

(defn- index-handler [_]
  {:status  200
   :headers {"content-type" "text/html; charset=utf-8"}
   :body    (layout "servo"
                    [:h1 {:class "text-3xl font-bold"} "servo"]
                    [:p {:class "mt-4"} "Ready."])})

(defn- health-handler [_]
  {:status  200
   :headers {"content-type" "application/json"}
   :body    (json/write-value-as-string {:status "ok"})})

(defn- routes [_db]
  [["/"        {:get index-handler}]
   ["/health"  {:get health-handler}]])

(defn- build-handler [db]
  (ring/ring-handler
   (ring/router (routes db))
   (ring/routes
    (ring/redirect-trailing-slash-handler)
    (ring/create-default-handler))))

(defrecord Handler [db ring-handler]
  clojure.lang.IFn
  (invoke [_ req] (ring-handler req))
  (applyTo [_ args] (apply ring-handler args))

  com.stuartsierra.component/Lifecycle
  (start [this]
    (assoc this :ring-handler (build-handler db)))
  (stop [this]
    (assoc this :ring-handler nil)))

(defn new-handler []
  (map->Handler {}))
