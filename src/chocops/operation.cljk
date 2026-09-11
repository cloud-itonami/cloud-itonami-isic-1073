(ns chocops.operation
  "OperationActor -- one cocoa/chocolate/sugar-confectionery plant
  operation = one supervised actor run, expressed as a langgraph-clj
  StateGraph. The advisor (ChocOpsAdvisor) is sealed into a single node
  (:advise); its proposal is ALWAYS routed through the independent
  Governor (:govern) before anything commits to the SSoT.

  Everything the actor depends on is injected, so each is a swap, not a
  rewrite:
    - the Store    (MemStore | DatomicStore, see `chocops.store`)
    - the Advisor  (mock today; real LLM is the next seam --
                     `chocops.advisor/Advisor` is already the injection
                     point, see its docstring)

  One graph run = one plant-operations coordination operation. No
  unbounded inner loop -- each operation is auditable and checkpointed.
  A plant's batch operating history is advanced by MANY operations
  (log-production-batch / schedule-maintenance /
  flag-food-safety-concern / coordinate-shipment), each its own
  independent graph run, and every commit/hold/approval-rejected
  decision fact lands in `chocops.store`'s append-only ledger
  (`store/append-ledger!`), so a batch's full operating history is
  always a query over an immutable log.

  Human-in-the-loop = real approval workflow:
  `interrupt-before #{:request-approval}` pauses the actor at the
  `:request-approval` node until a human plant operator resumes it with
  a decision. `:log-production-batch`, `:coordinate-shipment` (the two
  real actuation events, `chocops.governor/high-stakes`), and
  `:flag-food-safety-concern` ALWAYS reach this node when the Governor
  is clean -- see `chocops.governor/always-escalate-ops`. Mirrors
  `cerealops.operation` (cloud-itonami-isic-0111) node/edge structure
  exactly, wired to this repo's own advisor/governor/store. Unlike the
  agriculture verticals, this actor has no separate rollout `phase`
  gate -- `chocops.phase` is a PRODUCTION-BATCH workflow-state machine
  (intake->roasting->...->archived), a different concept entirely, and
  is not part of this graph; the Governor's verdict is authoritative
  here, same as the agriculture verticals' phase-3 (full autonomy)."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [chocops.advisor :as advisor]
            [chocops.governor :as governor]
            [chocops.store :as store]))

(defn- commit-fact
  "The audit fact written when a proposal commits. `:record` carries the
  operational payload the advisor proposed -- the durable production
  record itself lives on the Store's batch entry (see
  `apply-commit-side-effect!`); this ledger fact is the immutable audit
  trail of the decision."
  [request context proposal]
  {:t          :committed
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :commit
   :basis      (:cites proposal)
   :summary    (:summary proposal)
   :record     (:value proposal)})

(defn- commit-record [request _context proposal]
  {:effect  (:effect proposal)
   :path    [(:subject request)]
   :value   (or (:value proposal) {})
   :payload (:value proposal)})

(defn- apply-commit-side-effect!
  "Perform the Store-side effect of a committed proposal:
    - `:log-production-batch` -- re-persist the (already-registered)
      batch and mark it `:processed?` (one-way flag)
    - `:coordinate-shipment`  -- mark the batch's shipment
      `:shipment-finalized?` (one-way flag)
    - `:schedule-maintenance` / `:flag-food-safety-concern` -- pure
      coordination proposals; no batch mutation, only the ledger fact."
  [store {:keys [op subject]}]
  (case op
    :log-production-batch
    (store/log-batch store subject (store/production-batch store subject))
    :coordinate-shipment
    (store/finalize-shipment store subject)
    nil))

(defn build
  "Compiles an OperationActor graph bound to `store`. opts:
    :advisor      -- a `chocops.advisor/Advisor` (default: mock-advisor)
    :checkpointer -- a `langgraph.checkpoint/Checkpointer`
                     (default: in-memory `cp/mem-checkpointer`)"
  [store & [{:keys [advisor checkpointer]
             :or   {advisor      (advisor/mock-advisor)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :record      {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (advisor/-advise advisor store request)]
            {:proposal p :audit [(advisor/trace request p)]})))

      (g/add-node :govern
        (fn [{:keys [request context proposal]}]
          {:verdict (governor/check request context proposal store)}))

      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (cond
            (:hard? verdict)
            {:disposition :hold
             :audit [(governor/hold-fact request context verdict)]}

            (:escalate? verdict)
            {:disposition :escalate
             :audit [{:t :approval-requested
                      :op (:op request) :subject (:subject request)
                      :reason (cond (:high-stakes? verdict) :always-escalate
                                    :else :low-confidence)
                      :confidence (:confidence verdict)}]}

            :else
            {:disposition :commit
             :record (commit-record request context proposal)})))

      (g/add-node :request-approval
        (fn [{:keys [request context proposal approval verdict]}]
          (if (= :approved (:status approval))
            {:disposition :commit
             :record (assoc (commit-record request context proposal)
                            :payload (assoc (:value proposal)
                                            :approved-by (:by approval)))
             :audit [{:t :approval-granted :op (:op request)
                      :subject (:subject request) :by (:by approval)}]}
            {:disposition :hold
             :audit [(merge (governor/hold-fact request context
                                                (assoc verdict :violations
                                                       [{:rule :approver-rejected}]))
                            {:t :approval-rejected})]})))

      (g/add-node :commit
        (fn [{:keys [request context proposal]}]
          (apply-commit-side-effect! store request)
          (let [f (commit-fact request context proposal)]
            (store/append-ledger! store f)
            {:audit [f]})))

      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:governor-hold :approval-rejected} (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit   :commit
            :escalate :request-approval
            :hold)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}]
          (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))
