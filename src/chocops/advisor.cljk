(ns chocops.advisor
  "ChocOpsAdvisor -- the contained LLM/decision node. This actor's
  intelligence layer proposes back-office plant-coordination actions
  (production-batch logging, equipment-maintenance scheduling,
  food-safety concern flags, finished-product shipment coordination)
  based on batch state and operator input. The advisor is SEALED into
  the `:advise` step of the operation flow; every proposal is routed
  through the independent Governor before committing.

  The advisor makes proposals but has NO direct authority. Proposals are
  always censored by:
    1. Governor (batch registration, closed-op allowlist, moisture/
       cocoa-content/particle-size/process-temp/cadmium/viscosity/
       foreign-material/sanitation/allergen gates)
    2. Human operator (for the always-escalate high-stakes/food-safety
       ops)

  Current implementation is a mock advisor for testing. Production should
  use langchain/Claude or similar LLM backend (same seam point as
  `cerealops.advisor`, cloud-itonami-isic-0111)."
  (:require [chocops.store :as store]))

;; Protocol for swappable advisor implementations
(defprotocol Advisor
  (-advise [advisor store request]
    "Given store and request, return a proposal map with :op, :effect,
    :value, :cites, :summary, :confidence. `:value` carries the
    op-specific payload the Governor independently verifies against the
    already-registered batch record (moisture/cocoa-content/particle-
    size/process-temp/cadmium/viscosity/foreign-material/sanitation/
    allergen actuals live on the Store's batch record, NOT invented by
    the advisor -- see `chocops.governor`'s per-parameter violation
    checks, all of which read off `store/production-batch`)."))

;; Mock advisor for testing
(defrecord MockAdvisor []
  Advisor
  (-advise [_advisor store request]
    (let [{:keys [op subject jurisdiction]} request
          batch (store/production-batch store subject)
          juris (or jurisdiction (:jurisdiction batch))]
      (case op
        :log-production-batch
        {:op :log-production-batch
         :effect :propose
         :value {:batch-id subject :jurisdiction juris}
         :cites [{:spec "Codex-STAN-87" :jurisdiction juris}]
         :summary "Production batch (cocoa-processing/tempering/molding, output-quality data) proposed for logging into official plant records"
         :confidence 0.9}

        :schedule-maintenance
        {:op :schedule-maintenance
         :effect :propose
         :value {:batch-id subject
                 :equipment (:equipment request "conche")
                 :reason (:reason request "routine-schedule")}
         :cites [{:spec "Equipment-Manual"}]
         :summary "Equipment maintenance (conche/tempering-machine/molding-line/metal-detector) proposed"
         :confidence 0.85}

        :flag-food-safety-concern
        {:op :flag-food-safety-concern
         :effect :propose
         :value {:batch-id subject
                 :concern (:concern request "unspecified food-safety concern")
                 :recommended-action "plant-operator-review"}
         :cites [{:spec "Plant-HACCP-Plan"}]
         :summary "Food-safety concern (allergen cross-contact/cadmium residue/foreign material/sanitation) flagged for plant-operator review"
         :confidence 0.8}

        :coordinate-shipment
        {:op :coordinate-shipment
         :effect :propose
         :value {:batch-id subject :jurisdiction juris
                 :destination (:destination request "unspecified")}
         :cites [{:spec "Shipment-Manual" :jurisdiction juris}]
         :summary "Finished-product shipment coordination proposed"
         :confidence 0.85}

        ;; fallback -- unrecognized op. The Governor's closed allowlist
        ;; independently rejects this regardless of what the advisor says.
        {:op op
         :effect :propose
         :value {}
         :cites []
         :summary "Operation not recognized"
         :confidence 0.0}))))

(defn mock-advisor []
  (MockAdvisor.))

(defn trace
  "Audit trail entry for an advisor proposal. Recorded whenever a proposal
  is generated, regardless of whether it's approved."
  [request proposal]
  {:t :advisor-proposal
   :op (:op request)
   :subject (:subject request)
   :proposal-summary (:summary proposal)
   :confidence (:confidence proposal)})
