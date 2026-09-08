(ns chocops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300):
  this repo previously had NO demo page and no generator at all. This
  namespace drives the REAL actor stack (`chocops.operation` ->
  `chocops.governor` -> `chocops.store`, through `langgraph.graph/run*`)
  and renders whatever that run actually produced.

  Nothing on the page is typed by hand:
    - batch rows are read BACK OUT of the store with
      `chocops.store/production-batch` AFTER the run, so `:processed?` /
      `:shipment-finalized?` are post-run state, not seed state;
    - every product spec window comes from `chocops.facts/product-types`
      and every evidence requirement from `chocops.facts/jurisdictions`;
    - every HARD-hold rule name AND its detail string is the Governor's
      own `:violations` entry off the real ledger fact -- never a literal
      in this namespace;
    - the action-gate table is derived from the real
      `chocops.governor/allowed-ops` / `high-stakes` /
      `always-escalate-ops` vars joined with the dispositions the run
      actually reached;
    - the batch workflow table is produced by CALLING
      `chocops.phase/can-transition?`;
    - the approver-attribution column is a PROBE of the store at render
      time (see `approver-disclosure`), not a claim about it.

  Scenario shape (the ops and the request keys were confirmed against
  this repo's own `chocops.sim` demo driver, `clojure -M:dev:run`, which
  was run BEFORE this file was written):

    - `batch-001` (dark chocolate, US/FDA, every Governor parameter in
      window) walks the full clean lifecycle -- routine maintenance
      (auto-commits, the only op here that never needs a human), a
      production-batch log and a shipment coordination (BOTH always
      escalate, both approved), then a food-safety concern the human
      approver REJECTS -- the SOFT gate's other outcome, to contrast
      with a HARD hold that never reaches a human at all. It is then
      re-logged and re-shipped to trip both double-commit guards.
    - `batch-002` .. `batch-015` each violate exactly ONE Governor
      parameter, so each HARD rule is demonstrated in isolation.
    - `batch-999` is never registered at all.
    - one run is driven by an advisor that CLAIMS `:effect :actuate`
      (`->ActuationClaimingAdvisor`, a thin wrapper over the repo's own
      mock advisor that flips only that one key) -- the only way to
      exercise `:effect-not-propose`, since a well-behaved advisor can
      never produce it.

  All nineteen HARD rules `chocops.governor/check` can produce actually
  fire; `min-distinct-hard-rules` is the evidence floor that keeps it
  that way.

  Build-time invariants (`-main` THROWS, it does not warn):
    1. the run must produce at least one real `:governor-hold` fact;
    2. it must exercise at least `min-distinct-hard-rules` DISTINCT HARD
       rules -- so a governor change that silently stops firing a check
       fails the build instead of quietly shrinking the page;
    3. no run that HARD-held may also have asked a human (no
       `:approval-requested` in the same run's audit) -- the
       'HARD holds never reach a human' claim is MEASURED, not asserted;
    4. every HARD-check row rendered must trace to a rule present in the
       ledger, so the section cannot outlive the run that justified it;
    5. the SOFT gate must have shown BOTH outcomes (an approval granted
       AND an approval rejected) -- otherwise the approval trail and the
       approver probe are vacuous tables that read like a pass.

  Determinism: no timestamps, no randomness, no network. The two
  calibration dates in the seed are the only clock-derived values and
  they are deliberately NEVER rendered -- only the Governor's boolean
  answer about them is. Two consecutive builds are byte-identical
  (verify by diffing two runs).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [kotoba.lang.text :as str]
            [chocops.store :as store]
            [chocops.facts :as facts]
            [chocops.phase :as phase]
            [chocops.registry :as registry]
            [chocops.advisor :as advisor]
            [chocops.governor :as governor]
            [chocops.operation :as operation]
            [langgraph.graph :as g]))

(def ^:private plant-operator
  {:actor-id "plant-op-01" :role :plant-operator})

(def min-distinct-hard-rules
  "Evidence floor for invariant 2: the number of DISTINCT HARD rules the
  scenario below drives. It is every rule `chocops.governor/check` can
  produce. If a governor/store/advisor change makes fewer of them fire,
  the build fails rather than rendering a quietly thinner page. Raise
  this when the scenario grows; never lower it to make a build pass."
  19)

;; ----------------------------- the seed -----------------------------

(def ^:private day-ms (* 24 60 60 1000))

(defn- days-ago
  "Epoch-ms `n` days before now. The ONLY clock read in this namespace,
  and its value is never rendered -- the page shows the Governor's
  boolean verdict about the calibration, not the date. A fixed literal
  would silently flip from 'current' to 'overdue' the moment 90 days of
  wall-clock passed after it was written."
  [n]
  (- (System/currentTimeMillis) (* n day-ms)))

(def ^:private clean-batch
  "A dark-chocolate batch inside every one of the Governor's windows.
  Values are the same shape `chocops.sim`'s own `clean-batch` uses;
  each variant below overrides exactly one key of it."
  {:product-type :chocolate/dark
   :jurisdiction :us/fda
   :moisture-percent 1.0
   :cocoa-content-percent 40.0
   :particle-size-microns 20
   :process-temp-c 31.5
   :cadmium-ppm 0.3
   :viscosity-pa-s 3.0
   :foreign-material-detected? false
   :metal-detector-last-calibration-date (days-ago 10)
   :weight-variance-grams 10
   :declared-allergens #{}
   :cross-contact-risk #{}
   :sanitation-score 85
   :evidence-checklist [:cocoa-bean-intake-record :roasting-conching-log
                        :moisture-test :cocoa-content-test :particle-size-test
                        :tempering-temp-log :cadmium-test :allergen-declaration
                        :weight-check]})

(def ^:private seed-batches
  "The plant's registered batches, in registration order. `batch-001` is
  clean; every other batch differs from it in exactly ONE respect, named
  in `:why`, so each HARD rule below is demonstrated in isolation rather
  than as one row with a pile of violations. `:why` is this seed's own
  label for the reader -- the RULE and the DETAIL shown on the page are
  the Governor's, read off the ledger."
  [["batch-001" clean-batch
    "clean on every parameter — walks the full lifecycle"]
   ["batch-002" (assoc clean-batch :moisture-percent 2.5)
    "moisture above the product's target window"]
   ["batch-003" (assoc clean-batch :cocoa-content-percent 20.0)
    "cocoa solids below the declared category's minimum"]
   ["batch-004" (assoc clean-batch :particle-size-microns 60)
    "refining fineness coarser than the product maximum"]
   ["batch-005" (assoc clean-batch :process-temp-c 45.0)
    "tempering temperature outside the crystallization window"]
   ["batch-006" (assoc clean-batch :cadmium-ppm 1.4)
    "cadmium residue above the product's action level"]
   ["batch-007" (assoc clean-batch :viscosity-pa-s 7.5)
    "molding-line viscosity above the pourable maximum"]
   ["batch-008" (assoc clean-batch :foreign-material-detected? true)
    "foreign material found by this batch's own inspection"]
   ["batch-009" (assoc clean-batch :metal-detector-last-calibration-date (days-ago 200))
    "metal-detector calibration older than 90 days"]
   ["batch-010" (assoc clean-batch :weight-variance-grams 45)
    "finished-product weight drift beyond tolerance"]
   ["batch-011" (assoc clean-batch
                       :cross-contact-risk #{:milk :tree-nuts}
                       :declared-allergens #{:milk})
    "an allergen present via a shared line but not declared"]
   ["batch-012" (assoc clean-batch :sanitation-score 60)
    "plant sanitation/pest-control score below the minimum"]
   ["batch-013" (assoc clean-batch
                       :safety-concern-raised? true
                       :safety-concern-resolved? false)
    "a food-safety concern raised and still open"]
   ["batch-014" (assoc clean-batch
                       :evidence-checklist [:cocoa-bean-intake-record
                                            :roasting-conching-log
                                            :moisture-test])
    "jurisdiction evidence checklist incomplete"]
   ["batch-015" (assoc clean-batch :jurisdiction nil)
    "no jurisdiction on file — nothing to cite"]])

(def ^:private unregistered-subject
  "Driven below but deliberately NEVER registered."
  "batch-999")

;; ----------------------------- the rogue advisor -----------------------------

(defrecord ActuationClaimingAdvisor [inner]
  advisor/Advisor
  (-advise [_this store request]
    ;; Everything the repo's own mock advisor would say, with exactly one
    ;; key flipped: this proposal claims direct actuation authority for
    ;; itself. No in-band request can produce this, so it is the only way
    ;; to demonstrate that `:effect-not-propose` is really enforced.
    (assoc (advisor/-advise inner store request) :effect :actuate)))

;; ----------------------------- the real run -----------------------------

(defn- exec!
  "One supervised operation = one langgraph run. Returns the run result;
  `runs` accumulates each run's own audit channel, because the approval
  facts never reach the store ledger -- `chocops.operation`'s `:commit`
  node appends only `commit-fact`, and its `:hold` node only the hold."
  [runs actor tid request]
  (let [r (g/run* actor {:request request :context plant-operator} {:thread-id tid})]
    (swap! runs conj (assoc (select-keys request [:op :subject])
                            :thread tid
                            :audit (get-in r [:state :audit])
                            :disposition (get-in r [:state :disposition])))
    r))

(defn- resume!
  "Resume a run paused at `:request-approval` with a real human decision."
  [runs actor tid status by]
  (let [r (g/run* actor {:approval {:status status :by by}}
                  {:thread-id tid :resume? true})]
    (swap! runs conj {:thread tid :resume? true
                      :audit (get-in r [:state :audit])
                      :disposition (get-in r [:state :disposition])})
    r))

(defn run-scenario!
  "Drive `st` (any `chocops.store/Store`) through the scenario. Returns
  `{:db st :runs [..]}`. Called once for the rendered MemStore run and
  once more against a DatomicStore for the backend-parity section."
  [st]
  (doseq [[id data _why] seed-batches]
    (store/register-batch st id data))
  (let [runs (atom [])
        actor (operation/build st)
        rogue (operation/build st {:advisor (->ActuationClaimingAdvisor
                                             (advisor/mock-advisor))})]

    ;; --- batch-001: the full clean lifecycle --------------------------
    (exec! runs actor "t01" {:op :schedule-maintenance :subject "batch-001"
                             :equipment "tempering-machine" :reason "90-day-service"})

    (exec! runs actor "t02" {:op :log-production-batch :subject "batch-001"})
    (resume! runs actor "t02" :approved "plant-op-01")

    (exec! runs actor "t03" {:op :coordinate-shipment :subject "batch-001"
                             :destination "osaka-dc"})
    (resume! runs actor "t03" :approved "plant-op-01")

    ;; SOFT gate, other outcome: clean proposal, human approver says NO.
    (exec! runs actor "t04" {:op :flag-food-safety-concern :subject "batch-001"
                             :concern "乳成分の交差接触疑い（cross-contact suspicion）"})
    (resume! runs actor "t04" :rejected "plant-op-01")

    ;; --- HARD: the two double-commit guards ---------------------------
    (exec! runs actor "t05" {:op :log-production-batch :subject "batch-001"})
    (exec! runs actor "t06" {:op :coordinate-shipment :subject "batch-001"
                             :destination "osaka-dc"})

    ;; --- HARD: outside the closed op allowlist (equipment control) ----
    (exec! runs actor "t07" {:op :tempering/control :subject "batch-001"})

    ;; --- HARD: an advisor claiming actuation authority for itself -----
    (exec! runs rogue "t08" {:op :schedule-maintenance :subject "batch-001"
                             :equipment "conche" :reason "routine-schedule"})

    ;; --- HARD: one violated parameter per batch -----------------------
    (doseq [[i [id _data _why]] (map-indexed vector (rest seed-batches))
            :let [tid (format "t%02d" (+ 9 i))]]
      (if (= id "batch-015")
        ;; no jurisdiction on file -> nothing to cite. Driven through
        ;; :coordinate-shipment so the evidence check (log-only) cannot
        ;; also fire and blur the demonstration.
        (exec! runs actor tid {:op :coordinate-shipment :subject id
                               :destination "osaka-dc"})
        (exec! runs actor tid {:op :log-production-batch :subject id})))

    ;; --- HARD: a subject with no registered batch record at all -------
    (exec! runs actor "t24" {:op :schedule-maintenance :subject unregistered-subject
                             :equipment "conche" :reason "unscheduled-inspection"})

    {:db st :runs @runs}))

(defn run-demo! []
  (run-scenario! (store/mem-store)))

;; ----------------------------- helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kname [k] (if (keyword? k) (subs (str k) 1) (str k)))

(defn- code* [v] (str "<code>" (esc v) "</code>"))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- rows [xs] (str/join "\n" xs))

(defn- muted [s] (str "<span class=\"muted\">" s "</span>"))
(defn- ok [s] (str "<span class=\"ok\">" s "</span>"))
(defn- warn [s] (str "<span class=\"warn\">" s "</span>"))
(defn- critical [s] (str "<span class=\"critical\">" s "</span>"))
(defn- num* [s] (str "<span class=\"num\">" s "</span>"))

(defn- kw-list
  "Sorted, escaped, comma-joined keyword names -- sorted so a set never
  leaks its hash order into the rendered bytes."
  [xs]
  (if (seq xs)
    (str/join ", " (map #(esc (kname %)) (sort-by str xs)))
    nil))

(defn- section [title lede headers body-rows]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lede "</p>\n"
       "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n" body-rows "\n"
       "      </tbody>\n"
       "    </table>\n"
       "  </section>\n"))

;; ----------------------------- derived views -----------------------------

(defn- hard-holds [ledger] (filter #(= :governor-hold (:t %)) ledger))

(defn- hard-rule-groups
  "Groups the run's REAL `:governor-hold` facts by violated rule, in
  first-seen ledger order. Nothing here is a literal: if a rule stops
  firing its row disappears (and `-main`'s evidence floor fails)."
  [ledger]
  (->> (for [f (hard-holds ledger)
             v (:violations f)]
         (assoc v :subject (:subject f) :op (:op f)))
       (reduce (fn [acc {:keys [rule detail subject op]}]
                 (if (contains? acc rule)
                   (-> acc
                       (update-in [rule :count] inc)
                       (update-in [rule :subjects] conj subject)
                       (update-in [rule :ops] conj op))
                   (assoc acc rule {:rule rule :detail detail :count 1
                                    :order (count acc)
                                    :subjects [subject] :ops [op]})))
               {})
       vals
       (sort-by :order)))

(defn- op-dispositions
  "What each op ACTUALLY reached in this run, counted off the runs'
  own audit channels rather than predicted from the governor's vars."
  [runs]
  (reduce (fn [acc {:keys [op audit] :as r}]
            (if (or (:resume? r) (nil? op))
              acc
              (reduce (fn [a t] (update-in a [op t] (fnil inc 0)))
                      acc
                      (filter #{:committed :governor-hold :approval-requested}
                              (map :t audit)))))
          {}
          runs))

;; --- store approver attribution, DERIVED (never asserted) -------------

(defn- approver-in
  "Any value stored under an `approved-by` / `approved_by` key, whatever
  the key type. Returns nil when the map simply does not carry one."
  [m]
  (when (map? m)
    (some (fn [[k v]]
            (when (and v (contains? #{"approved-by" "approved_by"} (kname k))) v))
          m)))

(defn- registers-for
  "The places a committed op of this kind could have kept the approver,
  read back through the Store protocol / the store's own ledger at
  render time. This is the PROBE: whether the approver survives is
  decided by what is IN these maps now, not by anything this namespace
  claims about `chocops.operation` or `chocops.store`."
  [db op subject]
  (let [committed (filter #(and (= :committed (:t %))
                                (= op (:op %))
                                (= subject (:subject %)))
                          (store/ledger db))]
    (concat (map :record committed)
            (when (contains? #{:log-production-batch :coordinate-shipment} op)
              [(store/production-batch db subject)]))))

(defn- register-names [op]
  (case op
    :log-production-batch "committed ledger fact :record + the batch record"
    :coordinate-shipment  "committed ledger fact :record + the batch record"
    :flag-food-safety-concern "committed ledger fact :record (no batch mutation)"
    :schedule-maintenance "committed ledger fact :record (no batch mutation)"
    "committed ledger fact :record"))

(defn- approval-trail
  "One row per human decision the run actually took. `:decided-by` is
  read off the run's own audit fact; `:retained` is read back OUT of the
  store. The two are reported separately on purpose -- a reader must be
  able to tell 'nobody approved' from 'the store dropped it'."
  [db runs]
  (let [by-thread (into {} (for [r runs :when (not (:resume? r))] [(:thread r) r]))]
    (for [r runs
          :when (:resume? r)
          :let [f (last (filter #(#{:approval-granted :approval-rejected} (:t %)) (:audit r)))
                src (get by-thread (:thread r))
                o (:op src)
                s (:subject src)]
          :when f]
      {:thread (:thread r)
       :op o
       :subject s
       :outcome (:t f)
       :decided-by (:by f)
       :register (register-names o)
       :retained (when (= :approval-granted (:t f))
                   (some approver-in (registers-for db o s)))})))

(defn- approver-disclosure
  "The attribution sentence, DERIVED from the trail this run actually
  produced rather than stated as prose. If the store is later changed to
  keep the approver on a register it currently drops, this sentence
  changes with it -- a hard-coded claim here would have become a lie the
  moment someone fixed the store."
  [trail]
  (let [granted (filter #(= :approval-granted (:outcome %)) trail)
        ops (fn [xs] (str/join ", " (map #(code* %) (distinct (map :op xs)))))
        kept (filter :retained granted)
        lost (remove :retained granted)]
    (cond
      (empty? granted)
      "No approval was granted in this run, so there is nothing to attribute."

      (empty? lost)
      (str "Measured on this run: the approver survived on every register written by "
           (ops kept) ".")

      (empty? kept)
      (str "Measured on this run: the approver was dropped by every register written by "
           (ops lost) " — it exists only on the run's audit fact, which is why the "
           "<em>Decided by</em> column above can still name a person while the "
           "<em>Approver in the record?</em> column cannot. "
           "<code>chocops.operation</code>'s <code>:request-approval</code> node puts "
           "<code>:approved-by</code> on the record's <code>:payload</code>, but its "
           "<code>:commit</code> node writes <code>commit-fact</code>, whose "
           "<code>:record</code> is the advisor's <code>:value</code> — the "
           "<code>:payload</code> is never read, and the batch register is re-persisted "
           "from the batch record itself.")

      :else
      (str "Measured on this run: the approver survived on the registers written by "
           (ops kept) ", and was dropped by " (ops lost) " — audit only there."))))

;; ----------------------------- rows -----------------------------

(defn- last-fact-for [ledger subject]
  (last (filter #(= (:subject %) subject) ledger)))

(defn- status-cell [ledger subject]
  (let [f (last-fact-for ledger subject)]
    (cond
      (nil? f) (muted "no activity")
      (= :governor-hold (:t f))
      (critical (str "HARD hold &middot; " (kw-list (:basis f))))
      (= :approval-rejected (:t f)) (warn "approver rejected")
      (= :committed (:t f)) (ok "committed")
      :else (muted "in progress"))))

(defn- window-cell
  "actual / allowed window, both read from the batch record and
  `chocops.facts/product-types` -- with the Governor's OWN predicate
  from `chocops.registry` called to decide whether it is in or out."
  [out? actual allowed]
  (str (if out? (critical (esc actual)) (ok (esc actual)))
       " " (muted (str "/ " (esc allowed)))))

(defn- batch-row
  "Every cell is read back out of the store AFTER the run. The in/out
  verdicts are produced by CALLING the same `chocops.registry`
  predicates the Governor calls -- not copied from the hold text."
  [ledger [id _seed why]  b]
  (let [p (facts/product-type-by-id (:product-type b))
        j (facts/jurisdiction-by-id (:jurisdiction b))]
    (row (code* id)
         (if p (esc (:name p)) (critical "unknown product type"))
         (if j (esc (:name j)) (critical "none on file"))
         (window-cell (registry/moisture-out-of-target?
                       (:moisture-percent b) (:moisture-target-percent p)
                       (:moisture-tolerance-percent p))
                      (str (:moisture-percent b) "%")
                      (str (:moisture-target-percent p) "±" (:moisture-tolerance-percent p)))
         (window-cell (registry/cocoa-content-below-minimum?
                       (:cocoa-content-percent b) (:cocoa-content-min-percent p))
                      (str (:cocoa-content-percent b) "%")
                      (str "≥" (:cocoa-content-min-percent p)))
         (window-cell (registry/particle-size-exceeds-max?
                       (:particle-size-microns b) (:particle-size-max-microns p))
                      (str (:particle-size-microns b) "µm")
                      (str "≤" (:particle-size-max-microns p)))
         (window-cell (registry/process-temp-out-of-range?
                       (:process-temp-c b) (:process-temp-min-c p) (:process-temp-max-c p))
                      (str (:process-temp-c b) "℃")
                      (str (:process-temp-min-c p) "–" (:process-temp-max-c p)))
         (window-cell (registry/cadmium-exceeds-max?
                       (:cadmium-ppm b) (:cadmium-max-ppm p))
                      (str (:cadmium-ppm b) " ppm")
                      (str "≤" (:cadmium-max-ppm p)))
         (window-cell (registry/viscosity-exceeds-max?
                       (:viscosity-pa-s b) (:viscosity-max-pa-s p))
                      (str (:viscosity-pa-s b) " Pa·s")
                      (str "≤" (:viscosity-max-pa-s p)))
         (window-cell (registry/weight-variance-excessive? (:weight-variance-grams b) 20)
                      (str (:weight-variance-grams b) " g") "≤20")
         (window-cell (registry/sanitation-score-insufficient? (:sanitation-score b) 75)
                      (str (:sanitation-score b)) "≥75")
         (if (registry/foreign-material-detected? (:foreign-material-detected? b))
           (critical "detected") (ok "none"))
         ;; the calibration DATE is never rendered -- only the Governor's
         ;; boolean answer about it, so the page stays byte-stable.
         (if (registry/metal-detector-calibration-overdue?
              (:metal-detector-last-calibration-date b) (System/currentTimeMillis))
           (critical "overdue") (ok "current"))
         (if (registry/allergen-label-mismatch? (:cross-contact-risk b) (:declared-allergens b))
           (critical (str "undeclared: "
                          (kw-list (remove (set (:declared-allergens b))
                                           (:cross-contact-risk b)))))
           (ok (or (some->> (kw-list (:declared-allergens b)) (str "declared: "))
                   "no cross-contact")))
         (str (esc (count (:evidence-checklist b))) " / "
              (esc (count (:required-evidence j))))
         (if (and (true? (:safety-concern-raised? b))
                  (not (true? (:safety-concern-resolved? b))))
           (critical "open") (ok "none open"))
         (cond (:shipment-finalized? b) (ok "logged &amp; shipped")
               (:processed? b) (warn "logged, not yet shipped")
               :else (muted "not logged"))
         (status-cell ledger id)
         (esc why))))

(defn- unregistered-row [ledger subject]
  (row (code* subject)
       (critical "no batch record on file")
       (str (muted "— ") "(21 columns of batch metadata cannot exist for a batch that was never registered)")
       (status-cell ledger subject)))

(defn- hard-check-row [{:keys [rule detail count subjects ops]}]
  (row (code* rule)
       (num* count)
       (str/join " " (map #(code* %) (distinct subjects)))
       (str/join " " (map #(code* %) (distinct ops)))
       (esc detail)))

(defn- gate-row [dispositions op]
  (let [d (get dispositions op {})
        cell (fn [k klass] (when-let [n (get d k)] (str "<span class=\"" klass "\">" n "</span>")))]
    (row (code* op)
         (if (contains? governor/high-stakes op)
           (critical "yes &middot; real actuation")
           (muted "no"))
         (if (contains? governor/always-escalate-ops op)
           (warn "always &middot; even when the Governor is clean")
           (ok "only when the Governor is clean and confident"))
         (or (cell :committed "ok") (muted "—"))
         (or (cell :approval-requested "warn") (muted "—"))
         (or (cell :governor-hold "critical") (muted "—")))))

(defn- transition-row [from]
  (row (code* from)
       (let [nexts (filter #(phase/can-transition? from %) phase/all-phases)]
         (if (seq nexts)
           (str/join " " (map #(code* %) nexts))
           (muted "terminal &middot; nothing downstream")))
       (if (some #(phase/can-transition? % from) phase/all-phases)
         (muted "reachable")
         (ok "entry state"))))

(defn- basis-cell
  "`:basis` has two shapes in this actor's ledger: the Governor's rule
  keywords on a hold, and the advisor's citation maps on a commit. Both
  are rendered as they actually are."
  [basis]
  (cond
    (empty? basis) (muted "—")
    (every? keyword? basis) (str/join " " (map #(code* %) basis))
    :else (str/join " "
                    (for [c basis]
                      (code* (str (:spec c)
                                  (when-let [j (:jurisdiction c)] (str " · " j))))))))

(defn- ledger-row [{:keys [t op subject basis summary confidence]}]
  (row (case t
         :committed (ok "committed")
         :governor-hold (critical "governor-hold")
         :approval-rejected (warn "approval-rejected")
         (muted (esc (kname t))))
       (code* (or op :n-a))
       (code* subject)
       (basis-cell basis)
       (if confidence (num* (esc confidence)) (muted "—"))
       (esc (or summary ""))))

;; ----------------------------- backend parity -----------------------------

(defn- comparable-ledger
  "The ledger reduced to the fields a backend must reproduce identically.
  `:violations` detail strings are included -- a backend that lost the
  Governor's reasoning would still pass a rule-name-only comparison."
  [db]
  (mapv #(select-keys % [:t :op :subject :disposition :basis :violations
                         :confidence :summary :record])
        (store/ledger db)))

(defn- backend-parity
  "MEASURED, not claimed: the same scenario is replayed against a
  `DatomicStore` (the `langchain.db` EAV backend, whose ledger round-trips
  through an EDN blob codec) and the two ledgers are compared fact by
  fact. `chocops.store`'s docstring asserts the two backends are a swap;
  this is the page checking that assertion on this run."
  [mem-db]
  (let [other (:db (run-scenario! (store/datomic-store)))
        a (comparable-ledger mem-db)
        b (comparable-ledger other)
        pairs (map vector
                   (concat a (repeat nil))
                   (concat b (repeat nil)))
        n (max (count a) (count b))]
    {:mem-count (count a)
     :datomic-count (count b)
     :equal? (= a b)
     :first-divergence (first (keep-indexed (fn [i [x y]] (when (not= x y) i))
                                            (take n pairs)))}))

;; ----------------------------- rendering -----------------------------

(defn render
  "Renders the whole document from a `{:db .. :runs ..}` produced by
  `run-scenario!` (or any other real scenario)."
  [{:keys [db runs]}]
  (let [ledger (vec (store/ledger db))
        groups (hard-rule-groups ledger)
        trail (approval-trail db runs)
        dispositions (op-dispositions runs)
        n-hard (count (hard-holds ledger))
        parity (backend-parity db)]
    (str
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-1073 &middot; cocoa, chocolate &amp; sugar confectionery operator console</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Cocoa, chocolate and sugar confectionery manufacturing (ISIC 1073) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · logging a production batch and coordinating a shipment are always human-approved</span>\n"
     "</header>\n"
     "<main>\n"

     (section
      "Registered production batches (SSoT snapshot after the run)"
      (str "Every row is read back out of <code>chocops.store/production-batch</code> AFTER the run — "
           "build-time-generated by <code>chocops.render-html</code> "
           "(<code>clojure -M:dev:render-html</code>), never hand-written. "
           "The in-window / out-of-window verdict in each parameter cell is produced by CALLING the same "
           "<code>chocops.registry</code> predicate the Governor calls, against the same batch record and the "
           "same <code>chocops.facts/product-types</code> window — so a cell cannot disagree with the hold it "
           "caused. The metal-detector calibration DATE is deliberately not shown: it is the one clock-derived "
           "value in the seed, and printing it would make this page differ byte-for-byte between builds.")
      ["Batch" "Product" "Jurisdiction" "Moisture" "Cocoa solids" "Particle size"
       "Process temp" "Cadmium" "Viscosity" "Weight variance" "Sanitation"
       "Foreign material" "Detector calibration" "Allergen declaration"
       "Evidence" "Safety flag" "Lifecycle" "Last op status" "Seeded to demonstrate"]
      (rows (concat
             (for [[id _seed _why :as spec] seed-batches
                   :let [b (store/production-batch db id)]
                   :when b]
               (batch-row ledger spec b))
             [(unregistered-row ledger unregistered-subject)])))

     (section
      "Action gate — the closed allowlist and what each op actually reached"
      (str "The first two columns are membership of the REAL "
           "<code>chocops.governor/high-stakes</code> and "
           "<code>chocops.governor/always-escalate-ops</code> vars; the last three count what the runs in "
           "this scenario ACTUALLY produced, off their own audit channels. "
           "The allowlist itself is <code>chocops.governor/allowed-ops</code> — anything outside it "
           "(conching, tempering and molding-line control, and food-safety certification) is a permanent "
           "refusal, not a permission this actor could ever be granted. "
           "<code>" (esc :tempering/control) "</code> is driven below precisely to show that.")
      ["Op" "High-stakes actuation" "Needs a human" "Committed" "Escalated to a human" "HARD-held"]
      (rows (map (partial gate-row dispositions)
                 (concat (sort-by str governor/allowed-ops) [:tempering/control]))))

     (section
      (str "HARD Governor holds fired by this run (" n-hard " holds, " (count groups) " distinct rules)")
      (str "Grouped out of the run's real <code>:governor-hold</code> facts. Both the rule name and the "
           "detail sentence are the Governor's own <code>:violations</code> entry — this namespace does not "
           "contain either string. A HARD violation is un-overridable and never reaches a human approver at "
           "all, which the build verifies structurally (no <code>:approval-requested</code> in any held run's "
           "audit) rather than claiming here. Every rule <code>chocops.governor/check</code> can produce is "
           "represented; the build fails if fewer than <span class=\"num\">" min-distinct-hard-rules
           "</span> distinct rules fire.")
      ["Rule" "Times fired" "Batches" "Ops" "Detail (the Governor's own words)"]
      (rows (map hard-check-row groups)))

     (section
      "Human approval trail — and what the store actually kept"
      (str "The left half is the run's own audit fact; the right-most column is read back OUT of the store at "
           "render time by looking for an <code>approved-by</code> key in the registers that op writes. It is "
           "derived, not declared: if the store is later changed to keep the approver on a register it "
           "currently drops, this column starts saying <em>retained</em> without anyone editing this page. "
           (approver-disclosure trail))
      ["Op" "Batch" "Decision" "Decided by (audit fact)" "Store registers inspected" "Approver in the record?"]
      (rows (for [{:keys [op subject outcome decided-by register retained]} trail]
              (row (code* op)
                   (code* subject)
                   (if (= :approval-granted outcome) (ok "approved") (warn "rejected"))
                   (if decided-by (code* decided-by) (warn "not recorded on the audit fact"))
                   (esc register)
                   (cond
                     (not= :approval-granted outcome) (muted "n/a &middot; nothing committed")
                     retained (ok (str "retained &middot; " (code* retained)))
                     :else (warn "audit only — not retained in the store record"))))))

     (section
      "Production-batch workflow (chocops.phase)"
      (str "Produced by CALLING <code>chocops.phase/can-transition?</code> for every ordered pair of "
           "<code>chocops.phase/all-phases</code>. This is the batch's own state machine, NOT a rollout "
           "autonomy ladder — this actor has no phase gate, the Governor's verdict is authoritative on its "
           "own. Conching, tempering and molding are listed because a batch passes through them; they are "
           "operated by plant staff and this actor may not propose controlling them.")
      ["Phase" "May transition to" "Entry"]
      (rows (map transition-row phase/all-phases)))

     (section
      (str "Store backend parity — MemStore vs DatomicStore ("
           (if (:equal? parity) "identical" "DIVERGED") ")")
      (str "<code>chocops.store</code>'s docstring claims its two backends are a swap rather than a rewrite. "
           "This row is that claim being checked on this run: the whole scenario above was replayed against a "
           "<code>DatomicStore</code> (the <code>langchain.db</code> EAV backend, whose ledger round-trips "
           "through an EDN blob codec) and the two ledgers were compared fact by fact, including each hold's "
           "violation detail strings. "
           (if (:equal? parity)
             "They matched."
             (str "They did NOT match — first divergence at fact index "
                  (num* (esc (:first-divergence parity))) ".")))
      ["Backend" "Ledger facts" "Identical to MemStore?"]
      (rows [(row (code* "chocops.store/mem-store") (num* (:mem-count parity)) (muted "— (reference)"))
             (row (code* "chocops.store/datomic-store") (num* (:datomic-count parity))
                  (if (:equal? parity) (ok "yes &middot; fact for fact")
                      (critical (str "no &middot; first divergence at index " (esc (:first-divergence parity))))))]))

     (section
      (str "Audit ledger (this run — " (count ledger) " facts)")
      (str "Append-only decision-fact log: every commit, every hold, in the order the actor produced them. "
           "<code>:basis</code> carries the Governor's rule keywords on a hold and the advisor's citations on "
           "a commit — both are shown as they actually are. Note what is NOT here: the "
           "<code>:advisor-proposal</code> and <code>:approval-requested</code> facts live on each run's "
           "audit channel only, because <code>chocops.operation</code> appends just the terminal decision.")
      ["Fact" "Op" "Batch" "Basis" "Confidence" "Summary"]
      (rows (map ledger-row ledger)))

     "</main>\n"
     "<footer>\n"
     "  <p>Generated by <code>chocops.render-html</code> from a real "
     "<code>chocops.operation</code> → <code>chocops.governor</code> → <code>chocops.store</code> run "
     "driven through <code>langgraph.graph/run*</code>. "
     "Deterministic and timestamp-free: two consecutive builds are byte-identical.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

;; ----------------------------- build-time invariants -----------------------------

(defn assert-real-holds!
  "Invariants 1-5. Throws; never warns. A generator that renders a page
  with no HARD hold in it has not demonstrated the thing this repo
  exists to demonstrate, and a page whose HARD-check section outlived
  the run that justified it is a lie with a table around it."
  [{:keys [db runs]}]
  (let [ledger (vec (store/ledger db))
        holds (hard-holds ledger)
        fired (into #{} (mapcat #(map :rule (:violations %)) holds))
        groups (hard-rule-groups ledger)]
    (println "LEDGER-FACTS\t" (count ledger))
    (println "HARD-HOLDS\t" (count holds))
    (println "DISTINCT-HARD-RULES\t" (count fired))

    ;; 1 -- at least one real hold
    (when (zero? (count holds))
      (throw (ex-info (str "render-html: the run produced ZERO :governor-hold facts -- "
                           "refusing to write a console that shows no HARD hold")
                      {:ledger-facts (count ledger)})))

    ;; 2 -- evidence floor on DISTINCT rules
    (when (< (count fired) min-distinct-hard-rules)
      (throw (ex-info "render-html: fewer distinct HARD rules fired than the evidence floor requires"
                      {:fired (vec (sort-by str fired))
                       :count (count fired)
                       :floor min-distinct-hard-rules})))

    ;; 3 -- a HARD hold must never have asked a human
    (doseq [r runs]
      (let [ts (into #{} (map :t (:audit r)))]
        (when (and (contains? ts :governor-hold) (contains? ts :approval-requested))
          (throw (ex-info "render-html: a run that HARD-held ALSO escalated to a human approver"
                          {:thread (:thread r) :op (:op r) :subject (:subject r)})))))

    ;; 4 -- no rendered HARD-check row without a backing ledger fact
    (doseq [{:keys [rule]} groups]
      (when-not (contains? fired rule)
        (throw (ex-info "render-html: HARD-check row has no backing ledger fact" {:rule rule}))))

    ;; 5 -- the SOFT gate must have shown BOTH of its outcomes. Without
    ;; this floor an empty approval trail renders as a legitimate-looking
    ;; (but empty) table, and the derived attribution sentence degrades to
    ;; "nothing to attribute" -- silence that reads like a pass.
    (let [trail (approval-trail db runs)
          outcomes (frequencies (map :outcome trail))]
      (println "APPROVALS-GRANTED\t" (get outcomes :approval-granted 0))
      (println "APPROVALS-REJECTED\t" (get outcomes :approval-rejected 0))
      (when (zero? (get outcomes :approval-granted 0))
        (throw (ex-info (str "render-html: no approval was GRANTED -- the approval trail and the "
                             "approver-attribution probe would both be vacuous")
                        {:trail (count trail)})))
      (when (zero? (get outcomes :approval-rejected 0))
        (throw (ex-info "render-html: no approval was REJECTED -- the SOFT gate's other outcome was never demonstrated"
                        {:trail (count trail)}))))

    {:holds (count holds) :rules (vec (sort-by str fired))}))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        {:keys [holds rules]} (assert-real-holds! result)
        html (render result)]
    (when-let [p (.getParentFile (java.io.File. ^String out))] (.mkdirs p))
    (spit out html)
    (println "wrote" out
             (str "(" (count (store/ledger (:db result))) " ledger facts, "
                  holds " HARD holds over " (count rules) " distinct rules, "
                  (count (:runs result)) " runs, "
                  (count html) " bytes)"))
    (println "HARD-RULES\t" (str/join " " (map str rules)))))
