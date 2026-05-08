(ns servo.system-test
  (:require [clojure.test :refer [deftest testing is]]
            [com.stuartsierra.component :as component]
            [hato.client :as http]
            [servo.system :as system]))

(def ^:private test-port 18765)

(defn- test-config []
  {:env :test
   :server {:port test-port}
   :db {:dir (str "data/test-db-" (System/currentTimeMillis))}
   :stl-root "/tmp"})

(deftest system-lifecycle
  (testing "system starts, serves /health, and stops"
    (let [sys (component/start (system/build-system (test-config)))]
      (try
        (let [resp (http/get (str "http://localhost:" test-port "/health")
                             {:throw-exceptions? false})]
          (is (= 200 (:status resp)))
          (is (re-find #"\"ok\"" (:body resp))))
        (finally
          (component/stop sys))))))
