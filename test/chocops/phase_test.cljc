(ns chocops.phase-test
  (:require [clojure.test :refer [deftest is testing]]
            [chocops.phase :as phase]))

;; ──────────────────────── Phase Validity ──────────────────────

(deftest valid-phase-test
  (testing "intake is valid"
    (is (true? (phase/valid-phase? :intake))))

  (testing "tempering is valid"
    (is (true? (phase/valid-phase? :tempering))))

  (testing "archived is valid"
    (is (true? (phase/valid-phase? :archived))))

  (testing "invalid phase returns false"
    (is (false? (phase/valid-phase? :invalid)))))

;; ──────────────────────── Phase Transitions ──────────────────────

(deftest can-transition-test
  (testing "intake -> roasting is valid (forward progression)"
    (is (true? (phase/can-transition? :intake :roasting))))

  (testing "intake -> tempering is valid (skip roasting/conching)"
    (is (true? (phase/can-transition? :intake :tempering))))

  (testing "roasting -> intake is invalid (backward)"
    (is (false? (phase/can-transition? :roasting :intake))))

  (testing "tempering -> archived is valid (forward to end)"
    (is (true? (phase/can-transition? :tempering :archived))))

  (testing "archived -> intake is invalid (backward from end)"
    (is (false? (phase/can-transition? :archived :intake))))

  (testing "same phase is invalid"
    (is (false? (phase/can-transition? :tempering :tempering))))

  (testing "invalid phases return false"
    (is (false? (phase/can-transition? :invalid :tempering)))
    (is (false? (phase/can-transition? :tempering :invalid)))))
