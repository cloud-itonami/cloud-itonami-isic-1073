(ns chocops.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [chocops.governor :as governor]
            [chocops.store :as store]))

(def ^:private now-ms #?(:clj (System/currentTimeMillis) :cljs (.now js/Date)))
(def ^:private ten-days-ago (- now-ms (* 10 24 60 60 1000)))
(def ^:private hundred-days-ago (- now-ms (* 100 24 60 60 1000)))

(def ^:private clean-batch
  {:product-type :chocolate/dark
   :jurisdiction :us/fda
   :moisture-percent 1.0
   :cocoa-content-percent 40.0
   :particle-size-microns 20
   :process-temp-c 31.5
   :cadmium-ppm 0.3
   :viscosity-pa-s 3.0
   :foreign-material-detected? false
   :metal-detector-last-calibration-date ten-days-ago
   :weight-variance-grams 10
   :declared-allergens #{}
   :cross-contact-risk #{}
   :sanitation-score 85
   :evidence-checklist [:cocoa-bean-intake-record :roasting-conching-log :moisture-test :cocoa-content-test
                        :particle-size-test :tempering-temp-log :cadmium-test :allergen-declaration :weight-check]})

;; ──────────────────────── Batch Registration (generalized) ──────────────────────

(deftest batch-not-registered-violation-test
  (testing "log-production-batch against an unregistered batch is a hard violation"
    (let [req {:op :log-production-batch :subject "batch-ghost"}
          prop {:cites [{:spec "ISO-12345"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop (store/mem-store))]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :batch-not-registered) (:violations result)))))

  (testing "schedule-maintenance against an unregistered batch is also a hard violation"
    (let [req {:op :schedule-maintenance :subject "batch-ghost"}
          prop {:cites [] :value {} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop (store/mem-store))]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :batch-not-registered) (:violations result)))))

  (testing "a registered batch does not trigger this rule"
    (let [batch-id "batch-001"
          store (store/mem-store {:initial-batches {batch-id clean-batch}})
          req {:op :schedule-maintenance :subject batch-id}
          prop {:cites [] :value {} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :batch-not-registered) (:violations result)))))))

;; ──────────────────────── Effect Invariant ──────────────────────

(deftest effect-not-propose-violation-test
  (testing "a proposal asserting a non-:propose effect is a hard, permanent block"
    (let [batch-id "batch-001"
          store (store/mem-store {:initial-batches {batch-id clean-batch}})
          req {:op :schedule-maintenance :subject batch-id}
          prop {:cites [{:spec "Equipment-Manual"}] :value {:jurisdiction :us/fda}
                :effect :commit :confidence 0.9}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :effect-not-propose) (:violations result))))))

;; ──────────────────────── Hard Violations ──────────────────────

(deftest spec-basis-violation-test
  (testing "proposal with no jurisdiction citation is a hard violation"
    (let [batch-id "batch-001"
          store (store/mem-store {:initial-batches {batch-id clean-batch}})
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [] :value {:jurisdiction nil}}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :no-spec-basis) (:violations result)))))

  (testing "proposal with proper citation passes spec basis check"
    (let [batch-id "batch-001"
          store (store/mem-store {:initial-batches {batch-id clean-batch}})
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "Codex-STAN-87"}] :value {:jurisdiction :us/fda}}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:hard? result))))))

;; ──────────────────────── Moisture Violations ──────────────────────

(deftest moisture-violation-test
  (testing "batch with moisture out of range triggers hard violation"
    (let [batch-id "batch-001"
          store (store/mem-store {:initial-batches {batch-id (assoc clean-batch :moisture-percent 2.0)}})
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "Codex-STAN-87"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :moisture-out-of-target) (:violations result)))))

  (testing "batch with moisture in range passes"
    (let [batch-id "batch-001"
          store (store/mem-store {:initial-batches {batch-id clean-batch}})
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "Codex-STAN-87"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:hard? result))))))

;; ──────────────────────── Cocoa Content Violations ──────────────────────

(deftest cocoa-content-violation-test
  (testing "batch with cocoa content below the product's minimum triggers hard violation"
    (let [batch-id "batch-001"
          store (store/mem-store {:initial-batches {batch-id (assoc clean-batch :cocoa-content-percent 20.0)}})
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "Codex-STAN-87"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :cocoa-content-below-minimum) (:violations result)))))

  (testing "milk chocolate has a much lower cocoa-content floor than dark"
    (let [batch-id "batch-002"
          store (store/mem-store
                 {:initial-batches
                  {batch-id (assoc clean-batch
                                   :product-type :chocolate/milk
                                   :process-temp-c 29.5
                                   :cadmium-ppm 0.2
                                   :viscosity-pa-s 3.0
                                   :cocoa-content-percent 26.0)}})
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "Codex-STAN-87"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:hard? result))))))

;; ──────────────────────── Particle Size Violations ──────────────────────

(deftest particle-size-violation-test
  (testing "batch with particle size exceeding the product's maximum triggers hard violation"
    (let [batch-id "batch-001"
          store (store/mem-store {:initial-batches {batch-id (assoc clean-batch :particle-size-microns 60)}})
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "Codex-STAN-87"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :particle-size-exceeds-max) (:violations result))))))

;; ──────────────────────── Process Temperature Violations ──────────────────────

(deftest process-temp-violation-test
  (testing "batch with process temperature out of the tempering window triggers hard violation"
    (let [batch-id "batch-001"
          store (store/mem-store {:initial-batches {batch-id (assoc clean-batch :process-temp-c 40.0)}})
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "Codex-STAN-87"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :process-temp-out-of-range) (:violations result))))))

;; ──────────────────────── Cadmium Violations ──────────────────────

(deftest cadmium-violation-test
  (testing "batch with cadmium residue exceeding the product's limit triggers hard violation"
    (let [batch-id "batch-001"
          store (store/mem-store {:initial-batches {batch-id (assoc clean-batch :cadmium-ppm 1.5)}})
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "Codex-STAN-87"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :cadmium-exceeds-max) (:violations result)))))

  (testing "white chocolate has a much stricter cadmium limit than dark"
    (let [batch-id "batch-002"
          store (store/mem-store
                 {:initial-batches
                  {batch-id (assoc clean-batch
                                   :product-type :chocolate/white
                                   :moisture-percent 0.8
                                   :cocoa-content-percent 22.0
                                   :process-temp-c 28.5
                                   :viscosity-pa-s 3.0
                                   :cadmium-ppm 0.2)}})
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "Codex-STAN-87"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :cadmium-exceeds-max) (:violations result))))))

;; ──────────────────────── Viscosity Violations ──────────────────────

(deftest viscosity-violation-test
  (testing "batch with viscosity exceeding the product's maximum triggers hard violation"
    (let [batch-id "batch-001"
          store (store/mem-store {:initial-batches {batch-id (assoc clean-batch :viscosity-pa-s 9.0)}})
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "Codex-STAN-87"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :viscosity-exceeds-max) (:violations result))))))

;; ──────────────────────── Foreign Material Violations ──────────────────────

(deftest foreign-material-violation-test
  (testing "batch with detected foreign material triggers hard violation"
    (let [batch-id "batch-001"
          store (store/mem-store {:initial-batches {batch-id (assoc clean-batch :foreign-material-detected? true)}})
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "Codex-STAN-87"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :foreign-material-detected) (:violations result))))))

;; ──────────────────────── Metal Detector Calibration Violations ──────────────────────

(deftest metal-detector-calibration-violation-test
  (testing "batch with overdue metal-detector calibration triggers hard violation"
    (let [batch-id "batch-001"
          store (store/mem-store {:initial-batches {batch-id (assoc clean-batch :metal-detector-last-calibration-date hundred-days-ago)}})
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "Codex-STAN-87"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :metal-detector-calibration-overdue) (:violations result))))))

;; ──────────────────────── Weight Variance Violations ──────────────────────

(deftest weight-variance-violation-test
  (testing "batch with excessive weight variance triggers hard violation"
    (let [batch-id "batch-001"
          store (store/mem-store {:initial-batches {batch-id (assoc clean-batch :weight-variance-grams 30)}})
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "Codex-STAN-87"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :weight-variance-excessive) (:violations result))))))

;; ──────────────────────── Allergen Cross-Contact Violations ──────────────────────

(deftest allergen-label-mismatch-violation-test
  (testing "cross-contact risk without a matching declaration triggers hard violation"
    (let [batch-id "batch-001"
          store (store/mem-store
                 {:initial-batches
                  {batch-id (assoc clean-batch :cross-contact-risk #{:milk :tree-nuts} :declared-allergens #{})}})
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "Codex-STAN-87"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :allergen-label-mismatch) (:violations result)))))

  (testing "cross-contact risk WITH a complete declaration passes"
    (let [batch-id "batch-002"
          store (store/mem-store
                 {:initial-batches
                  {batch-id (assoc clean-batch :cross-contact-risk #{:milk :tree-nuts} :declared-allergens #{:milk :tree-nuts})}})
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "Codex-STAN-87"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :allergen-label-mismatch) (:violations result)))))))

;; ──────────────────────── Sanitation Score Violations ──────────────────────

(deftest sanitation-score-violation-test
  (testing "batch with insufficient sanitation score triggers hard violation"
    (let [batch-id "batch-001"
          store (store/mem-store {:initial-batches {batch-id (assoc clean-batch :sanitation-score 60)}})
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "Codex-STAN-87"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :sanitation-score-insufficient) (:violations result))))))

;; ──────────────────────── Food-Safety Flag Violations ──────────────────────

(deftest food-safety-flag-unresolved-violation-test
  (testing "batch with an unresolved food-safety flag triggers hard violation"
    (let [batch-id "batch-001"
          store (store/mem-store
                 {:initial-batches
                  {batch-id (assoc clean-batch
                                   :safety-concern-raised? true
                                   :safety-concern-resolved? false)}})
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "Codex-STAN-87"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :food-safety-flag-unresolved) (:violations result)))))

  (testing "batch with a resolved food-safety flag does not trigger this rule"
    (let [batch-id "batch-002"
          store (store/mem-store
                 {:initial-batches
                  {batch-id (assoc clean-batch
                                   :safety-concern-raised? true
                                   :safety-concern-resolved? true)}})
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "Codex-STAN-87"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (not (some #(= (:rule %) :food-safety-flag-unresolved) (:violations result)))))))

;; ──────────────────────── Escalation (Low Confidence) ──────────────────────

(deftest low-confidence-escalation-test
  (testing "low confidence proposal escalates even when hard checks pass"
    (let [batch-id "batch-001"
          store (store/mem-store {:initial-batches {batch-id clean-batch}})
          req {:op :schedule-maintenance :subject batch-id}
          prop {:cites [{:spec "Equipment-Manual"}] :value {:jurisdiction :us/fda} :confidence 0.5}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:ok? result)))
      (is (true? (:escalate? result)))
      (is (false? (:hard? result))))))

;; ──────────────────────── High Stakes Escalation ──────────────────────

(deftest high-stakes-escalation-test
  (testing "log-production-batch escalates even when all checks pass"
    (let [batch-id "batch-001"
          store (store/mem-store {:initial-batches {batch-id clean-batch}})
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "Codex-STAN-87"}] :value {:jurisdiction :us/fda} :confidence 0.95}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (false? (:ok? result)))
      (is (true? (:escalate? result)))
      (is (false? (:hard? result))))))

;; ──────────────────────── Already Processed Violation ──────────────────────

(deftest already-processed-violation-test
  (testing "batch already processed triggers hard violation"
    (let [batch-id "batch-001"
          store (store/mem-store
                 {:initial-batches
                  {batch-id {:product-type :chocolate/dark
                             :processed? true}}})
          req {:op :log-production-batch :subject batch-id}
          prop {:cites [{:spec "Codex-STAN-87"}] :value {:jurisdiction :us/fda} :confidence 0.8}
          result (governor/check req {:actor-id "gov-1"} prop store)]
      (is (true? (:hard? result)))
      (is (some #(= (:rule %) :already-processed) (:violations result))))))
