(ns servo.pattern
  "Pure functions for folder-path pattern matching.

  A pattern is a string like \"40k/{faction}/{unit_type}\". It parses into a
  vector of segment maps that can match against a folder path (split into a
  vector of folder-name strings) and capture placeholder values."
  (:require [clojure.string :as str]))

(defn- placeholder? [s]
  (and (str/starts-with? s "{")
       (str/ends-with? s "}")))

(defn- parse-segment [s]
  (if (placeholder? s)
    {:type :placeholder :name (subs s 1 (dec (count s)))}
    {:type :literal :value s}))

(defn parse-pattern
  "Splits `pattern-str` on \"/\" and returns a vector of segment maps.
  A segment surrounded by {} becomes {:type :placeholder :name <inner>};
  any other segment becomes {:type :literal :value <segment>}."
  [pattern-str]
  (mapv parse-segment (str/split pattern-str #"/")))

(defn match
  "Matches a parsed pattern against a vector of path segments.
  Returns a map of {placeholder-name -> folder-name} on success, nil otherwise.
  Lengths must be equal, and every literal segment must equal its path segment."
  [parsed-pattern path-segments]
  (when (= (count parsed-pattern) (count path-segments))
    (reduce
     (fn [captures [seg path-seg]]
       (case (:type seg)
         :literal     (if (= (:value seg) path-seg)
                        captures
                        (reduced nil))
         :placeholder (assoc captures (:name seg) path-seg)))
     {}
     (map vector parsed-pattern path-segments))))

(defn- segments-conflict-at-position? [a b]
  (and (= :literal (:type a))
       (= :literal (:type b))
       (not= (:value a) (:value b))))

(defn conflict?
  "Returns true if some path could match both parsed patterns.
  Two patterns conflict when they have the same length AND no position
  exists where both are literals with different values."
  [parsed-a parsed-b]
  (and (= (count parsed-a) (count parsed-b))
       (not (some true?
                  (map segments-conflict-at-position? parsed-a parsed-b)))))

(defn derive-tags
  "Returns the captured values as a vector, in map insertion order."
  [captures]
  (vec (vals captures)))

(defn derive-name
  "Joins path segments with \" / \" to form a human-readable collection name."
  [path-segments]
  (str/join " / " path-segments))
