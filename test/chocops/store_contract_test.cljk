(ns chocops.store-contract-test
  "MemStore ≡ DatomicStore parity for the Store protocol. Mirrors
  `cerealops.store-contract-test` (cloud-itonami-isic-0111)."
  (:require [clojure.test :refer [deftest is]]
            [chocops.store :as store]))

(defn- exercise [s]
  (store/register-batch s "batch-x" {:product-type :chocolate/dark :moisture-percent 1.0})
  ;; re-registering (update) exercises the identity-upsert path on
  ;; DatomicStore (:batch/id is :db.unique/identity) the same way
  ;; MemStore's plain `assoc` re-registration does.
  (store/register-batch s "batch-x" {:product-type :chocolate/dark :moisture-percent 1.2})
  (store/log-batch s "batch-x" (store/production-batch s "batch-x"))
  (store/append-ledger! s {:t :committed :op :log-production-batch :subject "batch-x"})
  (store/append-ledger! s {:t :approval-requested :op :coordinate-shipment :subject "batch-x"})
  {:batch    (store/production-batch s "batch-x")
   :absent   (store/production-batch s "no-such-batch")
   :processed? (store/batch-already-processed? s "batch-x")
   :ledger   (store/ledger s)})

(deftest mem-and-datomic-parity
  (let [mem (store/mem-store)
        dat (store/datomic-store)
        m (exercise mem)
        d (exercise dat)]
    (is (= (:batch m) (:batch d)))
    (is (= 1.2 (:moisture-percent (:batch m))) "re-registration upserts, not forks history")
    (is (nil? (:absent m)))
    (is (nil? (:absent d)))
    (is (true? (:processed? m)))
    (is (true? (:processed? d)))
    (is (= 2 (count (:ledger m))))
    (is (= 2 (count (:ledger d))))
    (is (= (:ledger m) (:ledger d)))))

(deftest datomic-store-seeded-batches
  (let [dat (store/datomic-store {:initial-batches
                                   {"batch-y" {:product-type :chocolate/milk}}})]
    (is (= {:product-type :chocolate/milk} (store/production-batch dat "batch-y")))
    (is (empty? (store/ledger dat)))))
