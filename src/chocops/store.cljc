(ns chocops.store
  "SSoT for the cocoa/chocolate/sugar-confectionery manufacturing
  coordinator, behind a `Store` protocol so the backend is a swap, not a
  rewrite -- the same seam every prior cloud-itonami actor in this fleet
  uses (mirrors `cerealops.store`, cloud-itonami-isic-0111; `caneops.store`,
  cloud-itonami-isic-0114):

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/chocops/store_contract_test.cljc).

  A production batch is the minimal unit of work: one processing run of
  a cocoa/chocolate/confectionery product, tracked from cocoa-bean (or
  sugar/other raw-material) intake through roasting, conching,
  tempering, molding, inspection, and shipment. A batch must be
  independently registered (verified off-actor and put on file) BEFORE
  ANY proposal referencing it can be considered by the Governor (see
  `chocops.governor`'s `batch-not-registered` invariant). Representative
  batch keys:
    - :product-type keyword product id (see `chocops.facts/product-types`)
    - :jurisdiction keyword jurisdiction id (see `chocops.facts/jurisdictions`)
    - :moisture-percent / :cocoa-content-percent / :particle-size-microns /
      :process-temp-c / :cadmium-ppm / :viscosity-pa-s finished-product
      actuals
    - :foreign-material-detected? true if metal-detector/magnet/optical-
      sorter inspection flagged tramp metal, stone, glass, or insect/
      rodent fragments
    - :sanitation-score 0-100 plant hygiene/pest-control score
    - :metal-detector-last-calibration-date epoch-ms of last
      metal-detection equipment calibration
    - :weight-variance-grams finished-product weight drift from target
    - :cross-contact-risk set of allergen keywords actually present via
      shared processing lines/equipment (e.g. #{:milk :tree-nuts})
    - :declared-allergens set of allergen keywords declared on label
    - :evidence-checklist evidence items present for the batch
    - :safety-concern-raised? / :safety-concern-resolved? food-safety flag
    - :processed? true once a `:log-production-batch` proposal commits
    - :shipment-finalized? true once a `:coordinate-shipment` proposal commits

  Batch data is intentionally open-shaped, so `DatomicStore` stores it as
  a single opaque EDN-blob attribute (`:batch/payload`, via
  `langchain-store.core`'s `enc`/`dec*`) rather than expanding it into
  per-key Datomic attributes -- the same blob convention every sibling
  DatomicStore already uses.

  The append-only audit ledger (`ledger`/`append-ledger!`) was this
  actor's core missing plumbing until now -- `chocops.operation`'s
  `:commit`/`:hold` graph nodes append every committed/held/
  approval-rejected decision fact here (the prior `append-fact`/
  `audit-trail` pair was dead code, only ever called from tests), so a
  plant's full operating history is always a query over an immutable
  log. The ledger stays append-only on every backend."
  (:require [langchain.db :as d]
            [langchain-store.core :as ls]))

(defprotocol Store
  (production-batch [store batch-id]
    "Retrieve a batch by id, or nil if it does not exist / is not yet
    registered.")
  (register-batch [store batch-id batch-data]
    "Independently verify/register a batch record. Used by tests,
    simulation, and plant-QA onboarding -- NOT by an advisor proposal
    (see `chocops.governor`'s `batch-not-registered` invariant: a batch
    must already be on file before any proposal can reference it).")
  (log-batch [store batch-id batch-data]
    "Register/update `batch-data` under `batch-id` and mark it processed
    (one-way flag). Used once a `:log-production-batch` proposal
    commits.")
  (finalize-shipment [store batch-id]
    "Mark an existing batch's shipment as finalized (one-way flag). Used
    once a `:coordinate-shipment` proposal commits.")
  (batch-already-processed? [store batch-id]
    "True only if the batch exists and has already been marked
    processed.")
  (batch-shipment-finalized? [store batch-id]
    "True only if the batch exists and its shipment has already been
    finalized.")
  (ledger [store]
    "The append-only audit ledger: every committed/held/approval-rejected
    decision fact, in append order.")
  (append-ledger! [store fact]
    "Append one immutable decision fact to the ledger. Returns fact."))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [batches ledger-atom]
  Store
  (production-batch [_store batch-id]
    (when batch-id
      (get @batches batch-id)))
  (register-batch [_store batch-id batch-data]
    (swap! batches assoc batch-id batch-data)
    batch-data)
  (log-batch [_store batch-id batch-data]
    (let [data (assoc batch-data :processed? true)]
      (swap! batches assoc batch-id data)
      data))
  (finalize-shipment [_store batch-id]
    (swap! batches update batch-id assoc :shipment-finalized? true)
    (get @batches batch-id))
  (batch-already-processed? [_store batch-id]
    (true? (:processed? (get @batches batch-id))))
  (batch-shipment-finalized? [_store batch-id]
    (true? (:shipment-finalized? (get @batches batch-id))))
  (ledger [_store] @ledger-atom)
  (append-ledger! [_store fact]
    (swap! ledger-atom conj fact)
    fact))

(defn mem-store
  "Create an in-memory store. `initial-batches` is an optional map of
  batch-id -> batch-record."
  [& [{:keys [initial-batches] :or {initial-batches {}}}]]
  (MemStore. (atom initial-batches) (atom [])))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  `:batch/payload` is stored as an EDN string blob (via
  `langchain-store.core`) so `langchain.db` doesn't try to expand an
  opaque, caller-defined batch record into sub-entities. The identity-
  schema builder, EDN-blob codec and seq-keyed event-log read/append are
  the shared kotoba-lang/langchain-store machinery (ADR-2607141600) --
  the seam ~190 actors hand-roll; this store keeps only its domain
  wiring."
  (ls/identity-schema [:batch/id :ledger/seq]))

(defrecord DatomicStore [conn]
  Store
  (production-batch [_store batch-id]
    (when batch-id
      (ls/dec* (d/q '[:find ?p .
                      :in $ ?bid
                      :where [?e :batch/id ?bid] [?e :batch/payload ?p]]
                    (d/db conn) batch-id))))
  (register-batch [store batch-id batch-data]
    (d/transact! conn [{:batch/id batch-id :batch/payload (ls/enc batch-data)}])
    (production-batch store batch-id))
  (log-batch [store batch-id batch-data]
    (register-batch store batch-id (assoc batch-data :processed? true)))
  (finalize-shipment [store batch-id]
    (register-batch store batch-id
                    (assoc (production-batch store batch-id) :shipment-finalized? true)))
  (batch-already-processed? [store batch-id]
    (true? (:processed? (production-batch store batch-id))))
  (batch-shipment-finalized? [store batch-id]
    (true? (:shipment-finalized? (production-batch store batch-id))))
  (ledger [_store] (ls/read-stream conn :ledger/seq :ledger/fact))
  (append-ledger! [store fact]
    (ls/append-blob! conn :ledger/seq :ledger/fact (count (ledger store)) fact)
    fact))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `initial-batches`
  (batch-id -> batch-record); empty when omitted."
  [& [{:keys [initial-batches] :or {initial-batches {}}}]]
  (let [s (->DatomicStore (d/create-conn schema))]
    (doseq [[batch-id batch-data] initial-batches]
      (register-batch s batch-id batch-data))
    s))
