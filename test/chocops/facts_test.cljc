(ns chocops.facts-test
  (:require [clojure.test :refer [deftest is testing]]
            [chocops.facts :as facts]))

;; ──────────────────────── Product Type Lookups ──────────────────────

(deftest product-type-by-id-test
  (testing "dark chocolate product type exists"
    (let [p (facts/product-type-by-id :chocolate/dark)]
      (is (some? p))
      (is (= (:id p) :chocolate/dark))
      (is (= (:cocoa-content-min-percent p) 35.0))
      (is (= (:cadmium-max-ppm p) 0.8))))

  (testing "milk chocolate product type exists"
    (let [p (facts/product-type-by-id :chocolate/milk)]
      (is (some? p))
      (is (= (:cocoa-content-min-percent p) 25.0))
      (is (= (:cadmium-max-ppm p) 0.3))))

  (testing "white chocolate product type exists"
    (let [p (facts/product-type-by-id :chocolate/white)]
      (is (some? p))
      (is (= (:cocoa-content-min-percent p) 20.0))
      (is (= (:cadmium-max-ppm p) 0.1))))

  (testing "hard candy (sugar confectionery) product type exists"
    (let [p (facts/product-type-by-id :confectionery/hard-candy)]
      (is (some? p))
      (is (= (:cocoa-content-min-percent p) 0.0))
      (is (= (:process-temp-min-c p) 145.0))))

  (testing "nonexistent product type returns nil"
    (is (nil? (facts/product-type-by-id :chocolate/nonexistent)))))

;; ──────────────────────── Jurisdiction Lookups ──────────────────────

(deftest jurisdiction-by-id-test
  (testing "JP MHLW jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :jp/mhlw)]
      (is (some? j))
      (is (some #{:cadmium-test} (:required-evidence j)))))

  (testing "US FDA jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :us/fda)]
      (is (some? j))
      (is (some #{:allergen-declaration} (:required-evidence j)))))

  (testing "EU EFSA jurisdiction exists"
    (let [j (facts/jurisdiction-by-id :eu/efsa)]
      (is (some? j))
      (is (some #{:tempering-temp-log} (:required-evidence j)))))

  (testing "nonexistent jurisdiction returns nil"
    (is (nil? (facts/jurisdiction-by-id :xx/unknown)))))

;; ──────────────────────── Evidence Completeness ──────────────────────

(deftest required-evidence-satisfied-test
  (testing "complete evidence checklist passes"
    (let [j (facts/jurisdiction-by-id :us/fda)
          evidence [:cocoa-bean-intake-record :roasting-conching-log :moisture-test :cocoa-content-test
                    :particle-size-test :tempering-temp-log :cadmium-test :allergen-declaration :weight-check]]
      (is (true? (facts/required-evidence-satisfied? j evidence)))))

  (testing "incomplete evidence fails"
    (let [j (facts/jurisdiction-by-id :us/fda)
          evidence [:cocoa-bean-intake-record :roasting-conching-log]]
      (is (false? (facts/required-evidence-satisfied? j evidence)))))

  (testing "accepts a raw jurisdiction id in place of a resolved map"
    (let [evidence [:cocoa-bean-intake-record :roasting-conching-log :moisture-test :cocoa-content-test
                    :particle-size-test :tempering-temp-log :cadmium-test :allergen-declaration :weight-check]]
      (is (true? (facts/required-evidence-satisfied? :us/fda evidence))))))

;; ──────────────────────── Processing Safety Predicates ──────────────────────

(deftest moisture-in-range-test
  (testing "moisture within tolerance passes"
    (let [p (facts/product-type-by-id :chocolate/dark)]
      (is (true? (facts/moisture-in-range? 1.0 p)))))

  (testing "moisture at lower tolerance boundary passes"
    (let [p (facts/product-type-by-id :chocolate/dark)]
      (is (true? (facts/moisture-in-range? 0.7 p)))))

  (testing "moisture below range fails"
    (let [p (facts/product-type-by-id :chocolate/dark)]
      (is (false? (facts/moisture-in-range? 0.3 p)))))

  (testing "moisture above range fails"
    (let [p (facts/product-type-by-id :chocolate/dark)]
      (is (false? (facts/moisture-in-range? 2.0 p))))))

(deftest cocoa-content-meets-minimum-test
  (testing "cocoa content at or above minimum passes"
    (let [p (facts/product-type-by-id :chocolate/dark)]
      (is (true? (facts/cocoa-content-meets-minimum? 35.0 p)))
      (is (true? (facts/cocoa-content-meets-minimum? 70.0 p)))))

  (testing "cocoa content below minimum fails"
    (let [p (facts/product-type-by-id :chocolate/dark)]
      (is (false? (facts/cocoa-content-meets-minimum? 20.0 p))))))

(deftest particle-size-within-max-test
  (testing "particle size at or below max passes"
    (let [p (facts/product-type-by-id :chocolate/dark)]
      (is (true? (facts/particle-size-within-max? 25 p)))
      (is (true? (facts/particle-size-within-max? 15 p)))))

  (testing "particle size above max fails"
    (let [p (facts/product-type-by-id :chocolate/dark)]
      (is (false? (facts/particle-size-within-max? 60 p))))))

(deftest process-temp-in-range-test
  (testing "process temp within window passes"
    (let [p (facts/product-type-by-id :chocolate/dark)]
      (is (true? (facts/process-temp-in-range? 31.5 p)))))

  (testing "process temp below window fails"
    (let [p (facts/product-type-by-id :chocolate/dark)]
      (is (false? (facts/process-temp-in-range? 20.0 p)))))

  (testing "process temp above window fails"
    (let [p (facts/product-type-by-id :chocolate/dark)]
      (is (false? (facts/process-temp-in-range? 40.0 p))))))

(deftest cadmium-within-max-test
  (testing "cadmium at or below the max passes"
    (let [p (facts/product-type-by-id :chocolate/dark)]
      (is (true? (facts/cadmium-within-max? 0.8 p)))
      (is (true? (facts/cadmium-within-max? 0.2 p)))))

  (testing "cadmium above the max fails"
    (let [p (facts/product-type-by-id :chocolate/dark)]
      (is (false? (facts/cadmium-within-max? 1.5 p))))))

(deftest viscosity-within-max-test
  (testing "viscosity at or below the max passes"
    (let [p (facts/product-type-by-id :chocolate/dark)]
      (is (true? (facts/viscosity-within-max? 5.0 p)))
      (is (true? (facts/viscosity-within-max? 2.0 p)))))

  (testing "viscosity above the max fails"
    (let [p (facts/product-type-by-id :chocolate/dark)]
      (is (false? (facts/viscosity-within-max? 9.0 p))))))
