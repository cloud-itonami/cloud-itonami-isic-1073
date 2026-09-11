(ns chocops.governor
  "Chocolate Governor -- the independent compliance layer that earns the
  ChocOpsAdvisor the right to commit. The LLM has no notion of:
    - Whether a batch's finished-product moisture stayed within its safe
      processing/storage window
    - Whether the batch's total cocoa content (or cocoa-butter content
      for white chocolate) meets the product's minimum compositional
      grade requirement
    - Whether the batch's particle size (refining fineness) exceeds the
      product's maximum for its declared grade
    - Whether the batch's tempering/cook process temperature fell within
      the product's crystallization/boil window
    - Whether the batch's cadmium residue exceeds the product's
      regulatory action level
    - Whether the batch's molding-line viscosity exceeds the product's
      maximum pourable window
    - Whether foreign material (metal/stone/glass/insect/rodent
      fragments) was detected in the batch
    - Whether the metal-detector calibration is current
    - Whether final product weight variance is acceptable
    - Whether allergen cross-contact labeling is complete and accurate
    - Whether plant sanitation/pest-control score is passed
    - Whether an open food-safety concern has been resolved
    - Whether the plant/batch record was independently verified and
      registered before any proposal is made against it

  This MUST be a separate system able to *reject* a proposal and fall back
  to HOLD.

  Unlike direct conching/tempering/molding-line control (NEVER done by
  this actor -- conche, tempering-machine, and molding-line operation
  remain exclusive to plant staff), the Governor operates on batch
  metadata: provenance, processing parameters, sanitation records, and
  food-safety flags. This is plant-operations coordination, not process
  control.

  CRITICAL: Any proposal involving food-safety concerns (cadmium
  residue, foreign-material detection, allergen cross-contact
  mislabeling, sanitation failures) ALWAYS escalates to human operator
  for final sign-off. The LLM's confidence is never sufficient for
  food-safety decisions.

  Hard violations (always HOLD, no override):
    1. Operation outside the closed allowlist (includes any proposal
       that would touch conching/tempering/molding-line control or
       food-safety certification)
    2. Proposal asserting an `:effect` other than `:propose`
    3. Plant/batch record not independently verified/registered before
       any proposal is made against it
    4. No jurisdiction citation (jurisdiction unknown -> can't verify reqs)
    5. Evidence incomplete (missing required-evidence per jurisdiction)
    6. Moisture out of target range (processing/storage safety)
    7. Cocoa content below the product's minimum compositional grade
    8. Particle size exceeds the product's maximum grade window
    9. Process (tempering/cook) temperature out of range
   10. Cadmium residue exceeds the product's regulatory action level
   11. Viscosity exceeds the product's maximum molding-line window
   12. Foreign material detected (metal/stone/glass/insect/rodent fragments)
   13. Metal-detector calibration overdue
   14. Weight variance excessive (molding/depositing scale drift risk)
   15. Allergen cross-contact mismatch (labeling / food-safety violation)
   16. Plant sanitation/pest-control score insufficient
   17. Food-safety flag unresolved (open concern, escalate required)
   18. Batch already processed / shipment already finalized (double-commit guards)

  Soft gates (always escalate for human):
    - Low confidence
    - Real actuation (`:log-production-batch`, `:coordinate-shipment`)
    - `:flag-food-safety-concern` (never auto-resolved by confidence alone)

  This design mirrors `sugarops.governor` and `millops.governor` but
  specializes on cocoa/chocolate/sugar-confectionery-specific concerns:
  cocoa-content compositional grading, tempering-curve/cadmium/viscosity
  processing safety, and allergen cross-contact labeling -- rather than
  sugar-refining-specific polarization/SO2/sulfite concerns or
  milling-specific mycotoxin contamination."
  (:require [chocops.facts :as facts]
            [chocops.registry :as registry]
            [chocops.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Logging a batch into production records (`:log-production-batch`) and
  coordinating shipment of finished product (`:coordinate-shipment`) are
  the two real-world actuation events this actor performs. Both require
  plant operator sign-off."
  #{:log-production-batch :coordinate-shipment})

(def always-escalate-ops
  "Operations that always require human sign-off, even when the Governor's
  hard checks are clean and confidence is high: the two high-stakes
  actuation events (`high-stakes`) plus `:flag-food-safety-concern` --
  a food-safety concern (cadmium residue, foreign-material detection,
  allergen cross-contact) is never auto-resolved by advisor confidence
  alone, it always needs a human look."
  (conj high-stakes :flag-food-safety-concern))

(def allowed-ops
  "Closed allowlist of proposal operations this actor may ever make. Any
  proposal for an operation outside this set -- most importantly direct
  conching/tempering/molding-line control (conche, tempering machine,
  and molding-line operation) or food-safety certification authority --
  is a hard, permanent block: this actor coordinates plant operations,
  it does not operate equipment and it does not certify food safety."
  #{:log-production-batch :schedule-maintenance :flag-food-safety-concern :coordinate-shipment})

;; ────────────────────────── Checks ──────────────────────────

(defn- op-not-allowed-violations
  "HARD, permanent block: any proposal outside the closed operation
  allowlist (e.g. direct conching/tempering/molding-line control, or a
  food-safety certification action) is refused unconditionally -- this
  actor has no authority to make such a proposal at all, let alone
  commit it."
  [{:keys [op]} _proposal]
  (when-not (contains? allowed-ops op)
    [{:rule :op-not-allowed
      :detail (str op " はこのactorの許可された提案種別 (log-production-batch/"
                  "schedule-maintenance/flag-food-safety-concern/coordinate-shipment) "
                  "に含まれない -- コンチング/テンパリング/成形ライン制御やfood-safety認証権限はこのactorに無い")}]))

(defn- effect-not-propose-violations
  "HARD invariant: this actor's proposals are always `:effect :propose` --
  it never claims direct write/actuation authority for itself. A proposal
  asserting any other effect is refused unconditionally."
  [_request proposal]
  (when-let [effect (:effect proposal)]
    (when (not= effect :propose)
      [{:rule :effect-not-propose
        :detail (str "この actor の提案は :propose 以外の :effect を持てない (got " effect ")")}])))

(defn- batch-not-registered-violations
  "HARD invariant: a plant/batch record must be independently verified/
  registered in the store BEFORE ANY proposal (not just shipment
  coordination) can be made against it -- this actor coordinates
  operations for an already-registered batch, it never invents or
  self-registers one from an unverified proposal."
  [{:keys [op subject]} st]
  (when (contains? allowed-ops op)
    (when-not (store/production-batch st subject)
      [{:rule :batch-not-registered
        :detail (str subject " はプラントに登録されたバッチ記録が無い -- 提案は進められない")}])))

(defn- spec-basis-violations
  "A proposal with no jurisdiction citation is a HARD violation -- never
  invent a jurisdiction's food-safety requirements."
  [{:keys [op]} proposal]
  (when (contains?
         #{:log-production-batch :coordinate-shipment :flag-food-safety-concern}
         op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :jurisdiction) (nil? (:jurisdiction value))))
        [{:rule :no-spec-basis
          :detail "公式仕様の引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:log-production-batch`, verify the batch's evidence checklist is
  complete per jurisdiction requirements."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when-not (and b
                     (facts/required-evidence-satisfied?
                      (:jurisdiction b)
                      (:evidence-checklist b)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(cocoa-bean-intake-record/roasting-conching-log/moisture-test/cocoa-content-test/particle-size-test/tempering-temp-log/cadmium-test等)が充足していない状態での提案"}]))))

(defn- moisture-out-of-target-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the batch's
  finished-product moisture falls within tolerance via
  `registry/moisture-out-of-target?`. Evaluated UNCONDITIONALLY."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:moisture-percent b)
                 (registry/moisture-out-of-target?
                  (:moisture-percent b)
                  (:moisture-target-percent p)
                  (:moisture-tolerance-percent p)))
        [{:rule :moisture-out-of-target
          :detail (str subject " の水分(" (:moisture-percent b)
                      "%)が目標範囲外 -- バッチ登録提案は進められない")}]))))

(defn- cocoa-content-below-minimum-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the batch's
  cocoa content (or cocoa-butter content for white chocolate) meets the
  product's minimum required compositional grade via
  `registry/cocoa-content-below-minimum?`."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:cocoa-content-percent b)
                 (registry/cocoa-content-below-minimum?
                  (:cocoa-content-percent b)
                  (:cocoa-content-min-percent p)))
        [{:rule :cocoa-content-below-minimum
          :detail (str subject " のカカオ分(" (:cocoa-content-percent b)
                      "%)が製品規格の最低値(" (:cocoa-content-min-percent p)
                      "%)を下回る -- バッチ登録提案は進められない")}]))))

(defn- particle-size-exceeds-max-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the batch's
  particle size (refining fineness) does not exceed the product's
  maximum via `registry/particle-size-exceeds-max?`."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:particle-size-microns b)
                 (registry/particle-size-exceeds-max?
                  (:particle-size-microns b)
                  (:particle-size-max-microns p)))
        [{:rule :particle-size-exceeds-max
          :detail (str subject " の粒度(" (:particle-size-microns b)
                      "μm)が製品規格上限(" (:particle-size-max-microns p)
                      "μm)を超過 -- バッチ登録提案は進められない")}]))))

(defn- process-temp-out-of-range-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the batch's
  process (tempering/cook) temperature falls within the product's
  window via `registry/process-temp-out-of-range?`. Evaluated
  UNCONDITIONALLY -- an out-of-window temper curve is one of the most
  common causes of fat bloom / structural defects in this industry."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:process-temp-c b)
                 (registry/process-temp-out-of-range?
                  (:process-temp-c b)
                  (:process-temp-min-c p)
                  (:process-temp-max-c p)))
        [{:rule :process-temp-out-of-range
          :detail (str subject " の工程温度(" (:process-temp-c b)
                      "℃)が製品規格範囲外 -- バッチ登録提案は進められない")}]))))

(defn- cadmium-exceeds-max-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the batch's
  cadmium residue does not exceed the product's maximum via
  `registry/cadmium-exceeds-max?`. Evaluated UNCONDITIONALLY -- this is
  the single most serious food-safety hazard specific to cocoa
  processing (heavy-metal bio-accumulation from soil)."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:cadmium-ppm b)
                 (registry/cadmium-exceeds-max?
                  (:cadmium-ppm b)
                  (:cadmium-max-ppm p)))
        [{:rule :cadmium-exceeds-max
          :detail (str subject " のカドミウム残留量(" (:cadmium-ppm b)
                      " ppm)が規制上限(" (:cadmium-max-ppm p)
                      " ppm)を超過 -- バッチ登録提案は進められない")}]))))

(defn- viscosity-exceeds-max-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the batch's
  molding-line viscosity does not exceed the product's maximum via
  `registry/viscosity-exceeds-max?`."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)
          p (when b (facts/product-type-by-id (:product-type b)))]
      (when (and b p (:viscosity-pa-s b)
                 (registry/viscosity-exceeds-max?
                  (:viscosity-pa-s b)
                  (:viscosity-max-pa-s p)))
        [{:rule :viscosity-exceeds-max
          :detail (str subject " の粘度(" (:viscosity-pa-s b)
                      " Pa·s)が製品規格上限(" (:viscosity-max-pa-s p)
                      " Pa·s)を超過 -- バッチ登録提案は進められない")}]))))

(defn- foreign-material-detected-violations
  "For `:log-production-batch`, INDEPENDENTLY verify the batch's own
  foreign-material-detection result via `registry/foreign-material-
  detected?`. A detection on THIS batch's own inspection is a hard,
  physical-hazard block -- distinct from `food-safety-flag-unresolved-
  violations` below, which covers a separately-raised, not-yet-resolved
  concern."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and b (registry/foreign-material-detected? (:foreign-material-detected? b)))
        [{:rule :foreign-material-detected
          :detail (str subject " で異物(金属/石/ガラス/昆虫・げっ歯類混入)が検出された -- バッチ登録提案は進められない")}]))))

(defn- now-epoch-ms
  "Current time in epoch milliseconds, portable across Clojure/
  ClojureScript. Isolated to this single call site so the rest of the
  namespace (and all of `chocops.registry`) stays free of host-clock
  calls."
  []
  #?(:clj (System/currentTimeMillis)
     :cljs (js/Date.now)))

(defn- metal-detector-calibration-overdue-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the
  metal-detector equipment's calibration is current (recalibration
  required every 90 days)."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and b (:metal-detector-last-calibration-date b)
                 (registry/metal-detector-calibration-overdue? (:metal-detector-last-calibration-date b) (now-epoch-ms)))
        [{:rule :metal-detector-calibration-overdue
          :detail (str subject " の異物検出機(金属探知機)校正が期限切れ -- バッチ登録提案は進められない")}]))))

(defn- weight-variance-excessive-violations
  "For `:log-production-batch`, INDEPENDENTLY verify the weight variance."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and b (:weight-variance-grams b)
                 (registry/weight-variance-excessive? (:weight-variance-grams b) 20))
        [{:rule :weight-variance-excessive
          :detail (str subject " の重量分散(" (:weight-variance-grams b)
                      "g)が許容範囲(20g)を超過 -- バッチ登録提案は進められない")}]))))

(defn- allergen-label-mismatch-violations
  "For `:log-production-batch`, INDEPENDENTLY verify allergen
  cross-contact declaration completeness via
  `registry/allergen-label-mismatch?` -- the single most common recall
  reason in chocolate/confectionery manufacturing (milk/tree-nut/
  peanut/soy/gluten cross-contact on shared processing lines)."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and b (:cross-contact-risk b)
                 (registry/allergen-label-mismatch? (:cross-contact-risk b) (:declared-allergens b)))
        [{:rule :allergen-label-mismatch
          :detail (str subject " のアレルゲン交差接触(cross-contact)宣言が不完全 -- バッチ登録提案は進められない")}]))))

(defn- sanitation-score-insufficient-violations
  "For `:log-production-batch`, INDEPENDENTLY verify that the plant's
  sanitation/pest-control score meets minimum requirements."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and b (:sanitation-score b)
                 (registry/sanitation-score-insufficient? (:sanitation-score b) 75))
        [{:rule :sanitation-score-insufficient
          :detail (str subject " のプラント衛生/防虫スコア(" (:sanitation-score b)
                      ")が最低要件(75)を下回る -- バッチ登録提案は進められない")}]))))

(defn- food-safety-flag-unresolved-violations
  "An unresolved food-safety flag is a HARD, un-overridable hold.
  Food-safety concerns (suspected cadmium over-residue, foreign
  material, allergen cross-contact) raised during production or
  inspection MUST be resolved before the batch can be logged. Evaluated
  UNCONDITIONALLY at `:log-production-batch`."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (let [b (store/production-batch st subject)]
      (when (and (true? (:safety-concern-raised? b))
                 (not (true? (:safety-concern-resolved? b))))
        [{:rule :food-safety-flag-unresolved
          :detail (str subject " は未解決の食品安全フラグがある -- バッチ登録提案は進められない")}]))))

(defn- already-processed-violations
  "For `:log-production-batch`, refuse to process the SAME batch twice, off
  a dedicated `:processed?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :log-production-batch)
    (when (store/batch-already-processed? st subject)
      [{:rule :already-processed
        :detail (str subject " は既に登録済み")}])))

(defn- already-shipment-finalized-violations
  "For `:coordinate-shipment`, refuse to finalize the SAME batch's shipment
  twice, off a dedicated `:shipment-finalized?` fact."
  [{:keys [op subject]} st]
  (when (= op :coordinate-shipment)
    (when (store/batch-shipment-finalized? st subject)
      [{:rule :already-shipment-finalized
        :detail (str subject " は既に出荷確定済み")}])))

(defn check
  "Censors a ChocOpsAdvisor proposal against the Governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}.

  Stakes (high-stakes actuation vs. always-escalate) are read off the
  REQUEST's `:op` -- not off the proposal -- since the operation being
  proposed (not the advisor's self-reported stake) is what determines
  whether a human must sign off."
  [request _context proposal st]
  (let [hard (into []
                   (concat (op-not-allowed-violations request proposal)
                           (effect-not-propose-violations request proposal)
                           (batch-not-registered-violations request st)
                           (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (moisture-out-of-target-violations request st)
                           (cocoa-content-below-minimum-violations request st)
                           (particle-size-exceeds-max-violations request st)
                           (process-temp-out-of-range-violations request st)
                           (cadmium-exceeds-max-violations request st)
                           (viscosity-exceeds-max-violations request st)
                           (foreign-material-detected-violations request st)
                           (metal-detector-calibration-overdue-violations request st)
                           (weight-variance-excessive-violations request st)
                           (allergen-label-mismatch-violations request st)
                           (sanitation-score-insufficient-violations request st)
                           (food-safety-flag-unresolved-violations request st)
                           (already-processed-violations request st)
                           (already-shipment-finalized-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        actuation? (boolean (high-stakes (:op request)))
        escalate-op? (boolean (always-escalate-ops (:op request)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not escalate-op?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? escalate-op?))
     :high-stakes? actuation?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
