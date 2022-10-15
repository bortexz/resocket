(ns build
  (:refer-clojure :exclude [test])
  (:require [org.corfield.build :as bb]))

(def lib 'io.github.bortexz/resocket)
(def version "0.1.0")

(defn- gha-output
  [k v]
  (println (str "::set-output name=" k "::" v)))

(defn test "Run the tests." [opts]
  (bb/run-tests opts))

(defn ci "Run the CI pipeline of tests (and build the JAR)." [opts]
  (-> opts
      (assoc :lib lib :version version)
      (bb/run-tests)
      (bb/clean)
      (assoc :src-pom "pom-template.xml")
      (bb/jar)))

(defn install "Install the JAR locally." [opts]
  (-> opts
      (assoc :lib lib :version version)
      (bb/install)))

(defn deploy "Deploy the JAR to Clojars." [opts]
  (-> opts
      (assoc :lib lib :version version)
      (bb/deploy))
  (gha-output "version" version))