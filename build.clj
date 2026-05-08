(ns build
  (:require [clojure.tools.build.api :as b]
            [jibbit.core :as jib]))

(def lib 'servo/servo)
(def main 'servo.main)
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def uber-file "target/servo.jar")

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs   ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis     @basis
                  :src-dirs  ["src"]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     @basis
           :main      main}))

(defn image [_]
  (uber nil)
  (jib/build {:image-type :tar
              :tar-file   "target/servo-image.tar"
              :image-name (str lib)
              :tar-base-image {:image-name "eclipse-temurin:21-jre"
                               :type :registry}
              :main main
              :uber-file uber-file}))
