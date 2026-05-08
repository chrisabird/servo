(ns servo.pattern-test
  (:require [clojure.test :refer [deftest testing is]]
            [servo.pattern :as pattern]))

(deftest parse-pattern-classifies-segments
  (testing "literals and placeholders are tagged correctly"
    (is (= [{:type :literal :value "40k"}
            {:type :placeholder :name "faction"}
            {:type :placeholder :name "unit_type"}]
           (pattern/parse-pattern "40k/{faction}/{unit_type}"))))
  (testing "all-literal pattern"
    (is (= [{:type :literal :value "a"}
            {:type :literal :value "b"}]
           (pattern/parse-pattern "a/b"))))
  (testing "all-placeholder pattern"
    (is (= [{:type :placeholder :name "x"}
            {:type :placeholder :name "y"}]
           (pattern/parse-pattern "{x}/{y}"))))
  (testing "single-segment pattern"
    (is (= [{:type :literal :value "lone"}]
           (pattern/parse-pattern "lone")))))

(deftest match-returns-captures-on-full-match
  (testing "literal + placeholders capture by name"
    (let [parsed (pattern/parse-pattern "40k/{faction}/{unit_type}")]
      (is (= {"faction" "space-marines" "unit_type" "tactical-squad"}
             (pattern/match parsed ["40k" "space-marines" "tactical-squad"])))))
  (testing "all-literal pattern matches and yields empty captures"
    (let [parsed (pattern/parse-pattern "a/b")]
      (is (= {} (pattern/match parsed ["a" "b"]))))))

(deftest match-returns-nil-when-length-differs
  (let [parsed (pattern/parse-pattern "40k/{faction}/{unit_type}")]
    (testing "shorter path"
      (is (nil? (pattern/match parsed ["40k" "space-marines"]))))
    (testing "longer path"
      (is (nil? (pattern/match parsed ["40k" "space-marines" "tactical-squad" "extra"]))))))

(deftest match-returns-nil-when-literal-mismatches
  (testing "first-position literal differs"
    (let [parsed (pattern/parse-pattern "40k/{faction}")]
      (is (nil? (pattern/match parsed ["aos" "stormcast"])))))
  (testing "literal in middle differs"
    (let [parsed (pattern/parse-pattern "{system}/40k/{unit}")]
      (is (nil? (pattern/match parsed ["games" "aos" "lord"]))))))

(deftest conflict-true-when-no-differing-literal-position
  (testing "two patterns of equal length where every position has at least one placeholder"
    (let [a (pattern/parse-pattern "40k/{faction}/{unit_type}")
          b (pattern/parse-pattern "{system}/space-marines/{unit_type}")]
      (is (true? (pattern/conflict? a b)))))
  (testing "matching literals in same positions still conflict"
    (let [a (pattern/parse-pattern "40k/{faction}")
          b (pattern/parse-pattern "40k/space-marines")]
      (is (true? (pattern/conflict? a b)))))
  (testing "two all-placeholder patterns of same length"
    (let [a (pattern/parse-pattern "{a}/{b}")
          b (pattern/parse-pattern "{c}/{d}")]
      (is (true? (pattern/conflict? a b))))))

(deftest conflict-false-when-lengths-differ
  (let [a (pattern/parse-pattern "40k/{faction}")
        b (pattern/parse-pattern "40k/{faction}/{unit_type}")]
    (is (false? (pattern/conflict? a b)))))

(deftest conflict-false-when-some-position-has-differing-literals
  (testing "first segment is a different literal in each pattern"
    (let [a (pattern/parse-pattern "40k/{faction}")
          b (pattern/parse-pattern "aos/{faction}")]
      (is (false? (pattern/conflict? a b)))))
  (testing "differing literal in middle"
    (let [a (pattern/parse-pattern "{system}/40k/{unit}")
          b (pattern/parse-pattern "{system}/aos/{unit}")]
      (is (false? (pattern/conflict? a b))))))

(deftest derive-tags-returns-values-only
  (testing "values returned as a vector of strings"
    (is (= ["space-marines" "tactical-squad"]
           (pattern/derive-tags {"faction" "space-marines"
                                 "unit_type" "tactical-squad"}))))
  (testing "empty captures yield empty vector"
    (is (= [] (pattern/derive-tags {})))))

(deftest derive-name-joins-with-separator
  (testing "joins segments with ' / '"
    (is (= "40k / space-marines / tactical-squad"
           (pattern/derive-name ["40k" "space-marines" "tactical-squad"]))))
  (testing "single segment"
    (is (= "alone" (pattern/derive-name ["alone"])))))
