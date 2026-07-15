(ns chocops.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [chocops.registry :as registry]))

;; ──────────────────────── Moisture Target ──────────────────────

(deftest moisture-out-of-target-test
  (testing "moisture at target with no tolerance returns false"
    (is (false? (registry/moisture-out-of-target? 1.0 1.0 0.3))))

  (testing "moisture within tolerance range returns false"
    (is (false? (registry/moisture-out-of-target? 1.2 1.0 0.3))))

  (testing "moisture below tolerance returns true (violation)"
    (is (true? (registry/moisture-out-of-target? 0.3 1.0 0.3))))

  (testing "moisture above tolerance returns true (violation)"
    (is (true? (registry/moisture-out-of-target? 2.0 1.0 0.3)))))

;; ──────────────────────── Cocoa Content ──────────────────────

(deftest cocoa-content-below-minimum-test
  (testing "cocoa content at minimum returns false (no violation)"
    (is (false? (registry/cocoa-content-below-minimum? 35.0 35.0))))

  (testing "cocoa content above minimum returns false"
    (is (false? (registry/cocoa-content-below-minimum? 45.0 35.0))))

  (testing "cocoa content below minimum returns true (violation)"
    (is (true? (registry/cocoa-content-below-minimum? 20.0 35.0)))))

;; ──────────────────────── Particle Size ──────────────────────

(deftest particle-size-exceeds-max-test
  (testing "particle size within max returns false (no violation)"
    (is (false? (registry/particle-size-exceeds-max? 20 25))))

  (testing "particle size at max returns false"
    (is (false? (registry/particle-size-exceeds-max? 25 25))))

  (testing "particle size exceeding max returns true (violation)"
    (is (true? (registry/particle-size-exceeds-max? 60 25)))))

;; ──────────────────────── Process Temperature ──────────────────────

(deftest process-temp-out-of-range-test
  (testing "process temp within range returns false (no violation)"
    (is (false? (registry/process-temp-out-of-range? 31.5 31.0 32.0))))

  (testing "process temp below range returns true (violation)"
    (is (true? (registry/process-temp-out-of-range? 20.0 31.0 32.0))))

  (testing "process temp above range returns true (violation)"
    (is (true? (registry/process-temp-out-of-range? 40.0 31.0 32.0)))))

;; ──────────────────────── Cadmium ──────────────────────

(deftest cadmium-exceeds-max-test
  (testing "cadmium within limit returns false (no violation)"
    (is (false? (registry/cadmium-exceeds-max? 0.3 0.8))))

  (testing "cadmium at limit returns false"
    (is (false? (registry/cadmium-exceeds-max? 0.8 0.8))))

  (testing "cadmium exceeding limit returns true (violation)"
    (is (true? (registry/cadmium-exceeds-max? 1.5 0.8)))))

;; ──────────────────────── Viscosity ──────────────────────

(deftest viscosity-exceeds-max-test
  (testing "viscosity within max returns false (no violation)"
    (is (false? (registry/viscosity-exceeds-max? 3.0 5.0))))

  (testing "viscosity at max returns false"
    (is (false? (registry/viscosity-exceeds-max? 5.0 5.0))))

  (testing "viscosity exceeding max returns true (violation)"
    (is (true? (registry/viscosity-exceeds-max? 9.0 5.0)))))

;; ──────────────────────── Metal Detector Calibration ──────────────────────

(deftest metal-detector-calibration-overdue-test
  (testing "recent calibration returns false (no violation)"
    ;; Assume calibrated 30 days ago
    (let [now #?(:clj (System/currentTimeMillis) :cljs (.now js/Date))
          thirty-days-ago (- now (* 30 24 60 60 1000))]
      (is (false? (registry/metal-detector-calibration-overdue? thirty-days-ago now)))))

  (testing "overdue calibration returns true (violation)"
    (let [now #?(:clj (System/currentTimeMillis) :cljs (.now js/Date))
          hundred-days-ago (- now (* 100 24 60 60 1000))]
      (is (true? (registry/metal-detector-calibration-overdue? hundred-days-ago now))))))

;; ──────────────────────── Weight Variance ──────────────────────

(deftest weight-variance-excessive-test
  (testing "variance within tolerance returns false (no violation)"
    (is (false? (registry/weight-variance-excessive? 15 20))))

  (testing "variance at tolerance returns false"
    (is (false? (registry/weight-variance-excessive? 20 20))))

  (testing "variance exceeding tolerance returns true (violation)"
    (is (true? (registry/weight-variance-excessive? 21 20)))))

;; ──────────────────────── Allergen Cross-Contact Labeling ──────────────────────

(deftest allergen-label-mismatch-test
  (testing "no cross-contact risk returns false (no risk) regardless of declaration"
    (is (false? (registry/allergen-label-mismatch? #{} #{}))))

  (testing "cross-contact risk fully covered by declaration returns false (no risk)"
    (is (false? (registry/allergen-label-mismatch? #{:milk :tree-nuts} #{:milk :tree-nuts}))))

  (testing "declaring more than the actual risk set is conservative and returns false"
    (is (false? (registry/allergen-label-mismatch? #{:milk} #{:milk :tree-nuts :soy}))))

  (testing "cross-contact risk not fully covered by declaration returns true (risk)"
    (is (true? (registry/allergen-label-mismatch? #{:milk :tree-nuts} #{:milk})))))

;; ──────────────────────── Foreign Material ──────────────────────

(deftest foreign-material-detected-test
  (testing "no detection returns false"
    (is (false? (registry/foreign-material-detected? false)))
    (is (false? (registry/foreign-material-detected? nil))))

  (testing "detection returns true"
    (is (true? (registry/foreign-material-detected? true)))))

;; ──────────────────────── Sanitation Score ──────────────────────

(deftest sanitation-score-insufficient-test
  (testing "score at minimum returns false (no violation)"
    (is (false? (registry/sanitation-score-insufficient? 75 75))))

  (testing "score above minimum returns false"
    (is (false? (registry/sanitation-score-insufficient? 85 75))))

  (testing "score below minimum returns true (violation)"
    (is (true? (registry/sanitation-score-insufficient? 74 75)))))
