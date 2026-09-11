(ns chocops.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a registered dark-chocolate
  production batch through a clean routine-op commit
  (`:schedule-maintenance`), an always-escalate high-stakes batch log
  (`:log-production-batch`, human approves -- marks the batch
  `:processed?`), an always-escalate food-safety concern (human
  rejects), and a hard-hold (unregistered batch), then prints the
  resulting audit ledger. Mirrors `cerealops.sim` (cloud-itonami-isic-0111)."
  (:require [langgraph.graph :as g]
            [chocops.operation :as operation]
            [chocops.store :as store]))

(def plant-operator {:actor-id "plant-op-01" :role :plant-operator})

(def ^:private clean-batch
  "A batch that satisfies every Governor hard check for dark chocolate
  (Codex STAN 87-1981 minimum 35% cocoa solids)."
  {:product-type :chocolate/dark
   :jurisdiction :us/fda
   :moisture-percent 1.0
   :cocoa-content-percent 40.0
   :particle-size-microns 20
   :process-temp-c 31.5
   :cadmium-ppm 0.3
   :viscosity-pa-s 3.0
   :foreign-material-detected? false
   :metal-detector-last-calibration-date
   #?(:clj (- (System/currentTimeMillis) (* 10 24 60 60 1000))
      :cljs (- (.now js/Date) (* 10 24 60 60 1000)))
   :weight-variance-grams 10
   :declared-allergens #{}
   :cross-contact-risk #{}
   :sanitation-score 85
   :evidence-checklist [:cocoa-bean-intake-record :roasting-conching-log :moisture-test
                        :cocoa-content-test :particle-size-test :tempering-temp-log
                        :cadmium-test :allergen-declaration :weight-check]})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "plant-op-01"}}
          {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "plant-op-01"}}
          {:thread-id tid :resume? true}))

(defn demo
  "Run the compiled StateGraph through a commit path, an
  escalate->approve->commit path, an escalate->reject->hold path, and
  a hard-hold path; print each result and the final audit ledger."
  []
  (let [st (store/mem-store)
        _  (store/register-batch st "batch-001" clean-batch)
        actor (operation/build st)]

    (println "=== Cocoa/Chocolate/Sugar-Confectionery Manufacturing Coordinator Demo ===")

    (println "\n== schedule-maintenance batch-001 (governor-clean, routine -> commit) ==")
    (println (exec-op actor "t1"
                      {:op :schedule-maintenance :subject "batch-001"
                       :equipment "tempering-machine" :reason "90-day-service"}
                      plant-operator))

    (println "\n== log-production-batch batch-001 (high-stakes, ALWAYS escalates -- operator approves) ==")
    (let [r (exec-op actor "t2"
                     {:op :log-production-batch :subject "batch-001" :jurisdiction :us/fda}
                     plant-operator)]
      (println r)
      (println "-- plant operator approves --")
      (println (approve! actor "t2"))
      (println "batch-001 now processed?" (store/batch-already-processed? st "batch-001")))

    (println "\n== flag-food-safety-concern batch-001 (ALWAYS escalates -- operator rejects) ==")
    (let [r (exec-op actor "t3"
                     {:op :flag-food-safety-concern :subject "batch-001"
                      :concern "乳成分の交差接触疑い（cross-contact suspicion）"}
                     plant-operator)]
      (println r)
      (println "-- plant operator rejects --")
      (println (reject! actor "t3")))

    (println "\n== schedule-maintenance batch-999 (unregistered -> HARD hold, no interrupt) ==")
    (println (exec-op actor "t4"
                      {:op :schedule-maintenance :subject "batch-999"
                       :equipment "conche" :reason "unscheduled-inspection"}
                      plant-operator))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger st)] (println f))

    {:ledger (store/ledger st)}))

(defn -main
  "clojure -M:run entrypoint."
  [& _args]
  (demo))

(comment
  ;; In a real REPL:
  (demo)
  )
