# cloud-itonami-isic-1073: Cocoa, Chocolate and Sugar Confectionery Manufacturing Coordination Actor

**ISIC Rev. 5 1073** — Manufacture of Cocoa, Chocolate and Sugar Confectionery

A distributed actor for autonomous, compliant coordination of cocoa/chocolate/sugar-confectionery plant operations: cocoa-bean (or sugar/other raw-material) intake → roasting/winnowing → conching → tempering (crystallization) → molding → moisture/cocoa-content/particle-size/process-temperature/cadmium/viscosity inspection → allergen cross-contact labeling → finished-product logistics. Sealed LLM advisor; independent Governor enforcement; append-only audit ledger. **Not equipment control.** Conche, tempering-machine, and molding-line operation and food-safety certification authority remain exclusive to licensed confectionery-plant staff and regulators.

## Scope

This actor coordinates **plant-operations workflow** for cocoa/chocolate/sugar-confectionery manufacturing (dark chocolate, milk chocolate, white chocolate, sugar confectionery such as hard candy):
- Production batch logging (cocoa-processing/tempering/molding batch, output-quality data)
- Equipment maintenance scheduling (conches, tempering machines, molding lines, metal detectors)
- Food-safety concern escalation (allergen cross-contact with milk/nuts, salmonella risk, cadmium residue, foreign-material contamination)
- Finished-product shipment coordination

**Out of scope:**
- Direct conching/tempering/molding-line equipment control (plant staff exclusive)
- Food-safety certification authority (human inspector/regulator only)
- Regulatory interpretation (proposals cite jurisdiction specifications; the Governor enforces only published requirements)

## Design

### Governor (Independent Compliance Layer)

The Governor is the separation-of-powers enforcement. It never trusts the advisor's confidence for anything safety- or compliance-relevant, and it always wins over the advisor.

- **Hard HOLD** (un-overridable):
  - Operation outside the closed allowlist (`:op-not-allowed`) — includes any proposal that would touch conching/tempering/molding-line control or food-safety certification
  - Proposal asserting an `:effect` other than `:propose` (`:effect-not-propose`)
  - Plant/batch record not independently verified/registered before any proposal is made against it (`:batch-not-registered`) — applies to every proposal op, not only shipment coordination
  - No jurisdiction citation (`:no-spec-basis`) — can't verify requirements without one
  - Evidence checklist incomplete (`:evidence-incomplete`)
  - Finished-product moisture outside the product's safe processing/storage range (`:moisture-out-of-target`)
  - Cocoa content (or cocoa-butter content for white chocolate) below the product's minimum compositional grade (`:cocoa-content-below-minimum`)
  - Particle size (refining fineness) exceeds the product's maximum grade window (`:particle-size-exceeds-max`)
  - Process (tempering/cook) temperature out of the product's window (`:process-temp-out-of-range`)
  - Cadmium residue exceeds the product's regulatory action level (`:cadmium-exceeds-max`)
  - Molding-line viscosity exceeds the product's maximum window (`:viscosity-exceeds-max`)
  - Foreign material detected on the batch's own inspection — metal/stone/glass/insect/rodent fragments (`:foreign-material-detected`)
  - Metal-detector calibration overdue (`:metal-detector-calibration-overdue`)
  - Finished-product weight variance excessive (`:weight-variance-excessive`)
  - Allergen cross-contact mismatch — a cross-contact risk (milk/tree-nuts/peanuts/soy/etc.) not fully covered by the declared-allergens label (`:allergen-label-mismatch`)
  - Plant sanitation/pest-control score insufficient (`:sanitation-score-insufficient`)
  - Unresolved food-safety flag (`:food-safety-flag-unresolved`)
  - Batch already processed / shipment already finalized (double-commit guards)
- **Escalate** (human sign-off always required):
  - `:log-production-batch` / `:coordinate-shipment` — real actuation events, always require plant-operator sign-off even when the Governor is otherwise clean
  - `:flag-food-safety-concern` — a food-safety concern (allergen cross-contact, cadmium residue, foreign material, salmonella risk) is never auto-resolved by advisor confidence alone
  - Low advisor confidence (below `governor/confidence-floor`, 0.6)
- **Commit** (advisor proposal approved; Governor clean; not a mandatory-escalation op):
  - Routine, low-stakes proposals only — in this actor's current allowlist that is effectively `:schedule-maintenance` when clean

### Operations (Proposals)

Closed allowlist — the advisor may **only** ever propose these four operation types, all `:effect :propose`:

- **`:log-production-batch`** — Log cocoa-processing/tempering/molding batch, output-quality data into production records (always requires human sign-off)
- **`:schedule-maintenance`** — Propose equipment maintenance for conches/tempering machines/molding lines/metal detectors (routine, low risk)
- **`:flag-food-safety-concern`** — Surface a food-safety concern (e.g. allergen cross-contact with milk/nuts, salmonella risk); always escalates
- **`:coordinate-shipment`** — Coordinate outbound confectionery shipment (always requires human sign-off)

Any proposal for an operation outside this allowlist — most importantly anything that would amount to direct conching/tempering/molding-line control, or food-safety certification — is refused unconditionally by the Governor (`:op-not-allowed`), regardless of advisor confidence.

## Testing

```bash
# Run full test suite
clojure -M:test

# Check code quality
clojure -M:lint

# Run demo simulation
clojure -M:run
```

## Standalone Use

This repo is **forkable outside the workspace**. If cloning standalone (not in the kotoba-lang monorepo), override `:local/root` paths in `deps.edn`:

```clojure
{:deps {io.github.kotoba-lang/langchain {:git/url "https://github.com/kotoba-lang/langchain" :git/tag "v0.1.0"}
        io.github.kotoba-lang/langgraph {:git/url "https://github.com/kotoba-lang/langgraph" :git/tag "v0.1.0"}}}
```

## License

AGPL-3.0-or-later. Forking/contribution welcome; see `CONTRIBUTING.md`.

## Security

Report security issues to the issue tracker or private disclosure; see `SECURITY.md`.

---

Part of **cloud-itonami**: autonomous actor fleet for regulated industries. See [github.com/cloud-itonami](https://github.com/cloud-itonami).
