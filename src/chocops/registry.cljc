(ns chocops.registry
  "Pure validation functions for cocoa/chocolate/sugar-confectionery
  manufacturing production parameters. These are called by the Governor
  to independently verify physical/operational constraints -- the
  advisor's confidence is NOT sufficient to override these checks.

  All functions here are pure arithmetic/set/boolean predicates with no
  host-clock or I/O calls, so this namespace stays trivially portable
  across Clojure/ClojureScript. Callers that need the current time (see
  `metal-detector-calibration-overdue?`) obtain it themselves via a
  `:clj`/`:cljs` reader-conditional at the call site (see
  `chocops.governor`)."
  (:require [clojure.set :as set]))

(defn moisture-out-of-target?
  "Independently verify that the batch's finished-product moisture falls
  within tolerance of the product's target moisture. Chocolate outside
  its moisture window risks sugar bloom/thickened viscosity (too high)
  or brittleness/handling faults (too low); boiled sugar confectionery
  outside range risks stickiness or premature graining."
  [actual-percent target-percent tolerance-percent]
  (or (< actual-percent (- target-percent tolerance-percent))
      (> actual-percent (+ target-percent tolerance-percent))))

(defn cocoa-content-below-minimum?
  "Independently verify that the batch's actual cocoa content (total
  cocoa solids, or cocoa-butter percentage for white chocolate) does not
  fall below the product's minimum required percentage. Below the
  product's minimum indicates the batch cannot legally bear its declared
  category name (Codex STAN 87-1981) -- a compositional/labeling
  violation with real consumer-protection consequences."
  [actual-percent min-percent]
  (< actual-percent min-percent))

(defn particle-size-exceeds-max?
  "Independently verify that the batch's actual particle size (refining
  fineness, microns) does not exceed the product's maximum allowable
  size. Particle size above the product's maximum is perceived as
  gritty/sandy on the tongue -- a texture/quality defect."
  [actual-microns max-microns]
  (> actual-microns max-microns))

(defn process-temp-out-of-range?
  "Independently verify that the batch's actual process temperature
  (tempering crystallization stage for chocolate, or cook/boil stage
  for sugar confectionery) falls within the product's expected window.
  Out of range indicates a tempering-curve fault -- for chocolate this
  risks an unstable cocoa-butter crystal form and subsequent fat bloom;
  for boiled sugar confectionery this risks the wrong sugar-glass/grain
  structure."
  [actual-celsius min-celsius max-celsius]
  (or (< actual-celsius min-celsius)
      (> actual-celsius max-celsius)))

(defn cadmium-exceeds-max?
  "Independently verify that the batch's actual cadmium residue (ppm,
  bio-accumulated by cocoa plants from soil) does not exceed the
  product's maximum allowable level. Cadmium above the regulatory/
  product action level is a serious, cocoa-specific heavy-metal
  food-safety hazard -- a hard, un-overridable stop."
  [actual-ppm max-ppm]
  (> actual-ppm max-ppm))

(defn viscosity-exceeds-max?
  "Independently verify that the batch's actual molding-line viscosity
  (Casson viscosity, Pa·s) does not exceed the product's maximum
  allowable level. Viscosity above the product's maximum risks
  incomplete mold fill and air-bubble entrapment on the molding line."
  [actual-pa-s max-pa-s]
  (> actual-pa-s max-pa-s))

(defn metal-detector-calibration-overdue?
  "Independently verify that the metal-detection equipment (catches
  tramp metal before it reaches the molding line or the finished
  product) was calibrated within the last 90 days.
  `last-calibration-epoch-ms` and `now-epoch-ms` are both epoch
  milliseconds -- callers obtain `now` via a `:clj`/`:cljs`
  reader-conditional, keeping this namespace free of any host-clock
  call."
  [last-calibration-epoch-ms now-epoch-ms]
  (> (- now-epoch-ms last-calibration-epoch-ms)
     (* 90 24 60 60 1000)))

(defn weight-variance-excessive?
  "Independently verify that a batch's finished-product weight variance
  (drift from target, in grams) does not exceed the maximum tolerance.
  Excessive variance indicates the molding/depositing line is out of
  calibration or the fill weight was measured incorrectly."
  [actual-variance-grams max-variance-grams]
  (> actual-variance-grams max-variance-grams))

(defn allergen-label-mismatch?
  "True when the batch's actual cross-contact allergen risk set (e.g.
  milk, tree nuts, peanuts, soy, gluten -- from shared processing
  lines/equipment) is not fully covered by `declared-allergens`
  (mislabeling / under-declaration risk -- a genuine food-safety hazard
  for allergic consumers, the single most common recall reason in
  chocolate/confectionery manufacturing). Declaring MORE than the
  actual risk set is conservative and never a risk."
  [cross-contact-risk declared-allergens]
  (boolean
   (seq (set/difference (set cross-contact-risk) (set declared-allergens)))))

(defn foreign-material-detected?
  "Independently verify a batch's foreign-material-detection result
  (metal, stone, glass, or insect/rodent fragments caught by
  magnet/metal-detector/optical-sorter inspection -- cocoa beans carry
  documented FDA Defect Action Levels for insect and rodent filth). Any
  detection is a genuine physical hazard -- this predicate simply
  coerces the raw fact to a boolean so the Governor's check functions
  stay uniform in shape with every other independently-verified
  physical constraint in this namespace."
  [actual-detected?]
  (boolean actual-detected?))

(defn sanitation-score-insufficient?
  "Independently verify that the plant's pre-production sanitation/
  pest-control score meets the minimum required. Score is 0-100,
  assessed by a third-party auditor against food-safety sanitation and
  rodent/insect infestation-control standards -- a significant HACCP
  concern specific to bulk cocoa/sugar storage and finished-product
  handling, and a key mitigation against Salmonella contamination
  (documented real-world chocolate/cocoa-powder recall cause)."
  [actual-score min-score-required]
  (< actual-score min-score-required))
