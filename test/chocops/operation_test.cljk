(ns chocops.operation-test
  "Exercises the compiled `chocops.operation/build` langgraph-clj
  StateGraph end-to-end (commit / hard-hold / escalate->approve->commit /
  escalate->reject->hold), plus the closed-allowlist and
  batch-registration hard blocks. Mirrors the coverage the prior
  `run-operation` pure-function tests had (see `chocops.governor-test`'s
  `effect-not-propose-violation-test` for the one prior case that
  exercised the Governor directly with a non-:propose proposal --
  unreachable through the graph now that the advisor always proposes
  `:effect :propose`), now driven through `langgraph.graph/run*` against
  the real compiled graph."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [chocops.operation :as operation]
            [chocops.store :as store]))

(def ^:private now-ms #?(:clj (System/currentTimeMillis) :cljs (.now js/Date)))
(def ^:private ten-days-ago (- now-ms (* 10 24 60 60 1000)))

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

(defn- run-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "op-1"}} {:thread-id tid :resume? true}))

(deftest run-operation-commit-test
  (testing "clean, non-actuation proposal (schedule-maintenance) commits directly, no interrupt"
    (let [st (store/mem-store {:initial-batches {"batch-001" clean-batch}})
          actor (operation/build st)
          request {:op :schedule-maintenance :subject "batch-001"
                   :equipment "conche" :reason "routine-schedule"}
          result (run-op actor "t1" request {:actor-id "op-1"})]
      (is (= :done (:status result)))
      (is (= :commit (get-in result [:state :disposition])))
      (is (= 1 (count (store/ledger st))))
      (is (= :committed (:t (first (store/ledger st))))))))

(deftest run-operation-hold-test
  (testing "hard-violating proposal (already-processed batch) produces a hold fact, no interrupt"
    (let [st (store/mem-store {:initial-batches {"batch-002" (assoc clean-batch :processed? true)}})
          actor (operation/build st)
          request {:op :log-production-batch :subject "batch-002"}
          result (run-op actor "t2" request {:actor-id "op-1"})]
      (is (= :done (:status result)))
      (is (= :hold (get-in result [:state :disposition])))
      (is (true? (get-in result [:state :verdict :hard?])))
      (is (some #(= :already-processed (:rule %)) (get-in result [:state :verdict :violations])))
      (is (= 1 (count (store/ledger st))))
      (is (= :governor-hold (:t (first (store/ledger st))))))))

(deftest run-operation-escalate-approve-test
  (testing "clean but high-stakes proposal (log-production-batch) interrupts before commit; approval commits and marks the batch processed"
    (let [st (store/mem-store {:initial-batches {"batch-003" clean-batch}})
          actor (operation/build st)
          request {:op :log-production-batch :subject "batch-003"}
          r1 (run-op actor "t3" request {:actor-id "op-1"})]
      (is (= :interrupted (:status r1)))
      (is (false? (store/batch-already-processed? st "batch-003")))
      (is (empty? (store/ledger st)) "no ledger fact until the human decides")
      (let [r2 (approve! actor "t3")]
        (is (= :done (:status r2)))
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (true? (store/batch-already-processed? st "batch-003")))
        (is (= 1 (count (store/ledger st))))
        (is (= :committed (:t (first (store/ledger st)))))))))

(deftest run-operation-escalate-reject-test
  (testing "escalated proposal (flag-food-safety-concern) rejected by human operator produces a hold, not a commit"
    (let [st (store/mem-store {:initial-batches {"batch-004" clean-batch}})
          actor (operation/build st)
          request {:op :flag-food-safety-concern :subject "batch-004" :concern "cross-contact suspicion"}
          r1 (run-op actor "t4" request {:actor-id "op-1"})]
      (is (= :interrupted (:status r1)))
      (let [r2 (reject! actor "t4")]
        (is (= :done (:status r2)))
        (is (= :hold (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/ledger st))))
        (is (= :approval-rejected (:t (first (store/ledger st)))))))))

(deftest run-operation-op-not-allowed-test
  (testing "an out-of-allowlist op (e.g. direct tempering-line control) is a hard, permanent block"
    (let [st (store/mem-store {:initial-batches {"batch-005" clean-batch}})
          actor (operation/build st)
          request {:op :control-tempering-line :subject "batch-005"}
          result (run-op actor "t5" request {:actor-id "op-1"})]
      (is (= :done (:status result)))
      (is (= :hold (get-in result [:state :disposition])))
      (is (some #(= :op-not-allowed (:rule %)) (get-in result [:state :verdict :violations]))))))

(deftest run-operation-shipment-batch-not-registered-test
  (testing "coordinating shipment for a never-registered batch is a hard block"
    (let [st (store/mem-store)
          actor (operation/build st)
          request {:op :coordinate-shipment :subject "batch-999"}
          result (run-op actor "t6" request {:actor-id "op-1"})]
      (is (= :done (:status result)))
      (is (= :hold (get-in result [:state :disposition])))
      (is (some #(= :batch-not-registered (:rule %)) (get-in result [:state :verdict :violations]))))))

(deftest run-operation-coordinate-shipment-approve-test
  (testing "coordinate-shipment escalates and, once approved, marks the batch shipment finalized"
    (let [st (store/mem-store {:initial-batches {"batch-006" clean-batch}})
          actor (operation/build st)
          request {:op :coordinate-shipment :subject "batch-006"}
          r1 (run-op actor "t7" request {:actor-id "op-1"})]
      (is (= :interrupted (:status r1)))
      (is (false? (store/batch-shipment-finalized? st "batch-006")))
      (let [r2 (approve! actor "t7")]
        (is (= :done (:status r2)))
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (true? (store/batch-shipment-finalized? st "batch-006")))))))
