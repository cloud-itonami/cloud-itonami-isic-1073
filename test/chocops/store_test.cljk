(ns chocops.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [chocops.store :as store]))

;; ──────────────────────── Store Construction ──────────────────────

(deftest mem-store-creation
  (testing "Create empty store"
    (let [st (store/mem-store)]
      (is (some? st))
      (is (satisfies? store/Store st))))

  (testing "Create store with initial batches"
    (let [batches {"batch-001" {:product-type :chocolate/dark :moisture-percent 1.0}}
          st (store/mem-store {:initial-batches batches})]
      (is (some? st))
      (is (satisfies? store/Store st)))))

;; ──────────────────────── Batch Retrieval ──────────────────────

(deftest production-batch-test
  (testing "retrieve an existing batch"
    (let [batch-data {:product-type :chocolate/dark :moisture-percent 1.0}
          st (store/mem-store {:initial-batches {"batch-001" batch-data}})
          result (store/production-batch st "batch-001")]
      (is (= result batch-data))))

  (testing "nonexistent batch returns nil"
    (let [st (store/mem-store)
          result (store/production-batch st "nonexistent")]
      (is (nil? result))))

  (testing "nil batch-id returns nil (never falls through to a default)"
    (let [st (store/mem-store {:initial-batches {"batch-001" {:product-type :chocolate/dark}}})]
      (is (nil? (store/production-batch st nil))))))

;; ──────────────────────── Batch Status Checks ──────────────────────

(deftest batch-already-processed-test
  (testing "processed batch is detected"
    (let [st (store/mem-store {:initial-batches {"batch-001" {:processed? true}}})
          result (store/batch-already-processed? st "batch-001")]
      (is (true? result))))

  (testing "unprocessed batch returns false"
    (let [st (store/mem-store {:initial-batches {"batch-001" {:processed? false}}})
          result (store/batch-already-processed? st "batch-001")]
      (is (false? result))))

  (testing "nonexistent batch returns false"
    (let [st (store/mem-store)
          result (store/batch-already-processed? st "batch-001")]
      (is (false? result)))))

(deftest batch-shipment-finalized-test
  (testing "finalized shipment is detected"
    (let [st (store/mem-store {:initial-batches {"batch-001" {:shipment-finalized? true}}})
          result (store/batch-shipment-finalized? st "batch-001")]
      (is (true? result))))

  (testing "non-finalized shipment returns false"
    (let [st (store/mem-store {:initial-batches {"batch-001" {:shipment-finalized? false}}})
          result (store/batch-shipment-finalized? st "batch-001")]
      (is (false? result)))))

;; ──────────────────────── Batch Registration ──────────────────────

(deftest register-batch-test
  (testing "registering a new batch"
    (let [st (store/mem-store)
          batch-data {:product-type :chocolate/dark}
          result (store/register-batch st "batch-001" batch-data)]
      (is (= batch-data result))
      (is (= batch-data (store/production-batch st "batch-001"))))))

(deftest log-batch-test
  (testing "logging a batch marks it as processed"
    (let [st (store/mem-store)
          batch-data {:product-type :chocolate/dark}
          _ (store/log-batch st "batch-001" batch-data)]
      (is (true? (:processed? (store/production-batch st "batch-001"))))))

  (testing "logging preserves batch data"
    (let [st (store/mem-store)
          batch-data {:product-type :chocolate/dark :moisture-percent 1.0}
          _ (store/log-batch st "batch-001" batch-data)]
      (is (= (:product-type (store/production-batch st "batch-001")) :chocolate/dark))
      (is (= (:moisture-percent (store/production-batch st "batch-001")) 1.0)))))

;; ──────────────────────── Shipment Finalization ──────────────────────

(deftest finalize-shipment-test
  (testing "finalizing a batch marks it as finalized"
    (let [st (store/mem-store {:initial-batches {"batch-001" {:product-type :chocolate/dark}}})
          _ (store/finalize-shipment st "batch-001")]
      (is (true? (:shipment-finalized? (store/production-batch st "batch-001")))))))

;; ──────────────────────── Audit Ledger ──────────────────────

(deftest ledger-test
  (testing "ledger is initially empty"
    (let [st (store/mem-store)
          result (store/ledger st)]
      (is (empty? result))))

  (testing "appended facts appear in the ledger, in append order"
    (let [st (store/mem-store)
          fact1 {:t :test-fact :detail "test 1"}
          fact2 {:t :test-fact :detail "test 2"}
          _ (store/append-ledger! st fact1)
          _ (store/append-ledger! st fact2)
          result (store/ledger st)]
      (is (= (count result) 2))
      (is (= (first result) fact1))
      (is (= (second result) fact2)))))

(deftest append-ledger-test
  (testing "appending a fact increases ledger length and returns the fact"
    (let [st (store/mem-store)
          fact {:t :governor-hold :op :log-production-batch}
          result (store/append-ledger! st fact)]
      (is (= result fact))
      (is (= (count (store/ledger st)) 1))
      (is (= (first (store/ledger st)) fact)))))
