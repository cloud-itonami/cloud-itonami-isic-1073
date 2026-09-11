(ns chocops.facts
  "Reference facts for cocoa/chocolate/sugar-confectionery manufacturing:
  product-type processing parameters (moisture/cocoa-content/particle-
  size/process-temp/cadmium/viscosity windows), jurisdiction evidence-
  checklist requirements. This namespace contains pure lookup functions
  for regulatory/food-safety compliance checks -- the Governor calls
  these to independently validate proposals; the advisor's confidence is
  never sufficient on its own."
  (:require [clojure.set :as set]))

(def product-types
  "Valid cocoa/chocolate/sugar-confectionery product categories and
  their safe processing windows. `cocoa-content-min-percent` is the
  minimum total-cocoa-solids percentage (Codex STAN 87-1981) a
  chocolate product must meet to bear its declared category name --
  for white chocolate this represents the minimum cocoa-butter
  percentage instead (white chocolate has zero non-fat cocoa solids by
  definition), and for pure sugar confectionery (no cocoa) it is 0 and
  the check never fires. `particle-size-max-microns` is the maximum
  allowable particle size from refining/grinding -- coarser particles
  are perceived as gritty/sandy on the tongue. `process-temp-min-c` /
  `process-temp-max-c` describe the tempering crystallization window
  (chocolate -- sets the stable Form V cocoa-butter crystal that
  prevents fat bloom) or the boil/cook window (sugar confectionery --
  sets the sugar-glass/grain structure). `cadmium-max-ppm` is the
  maximum allowable cadmium (a heavy metal cocoa beans bio-accumulate
  from soil, EU Regulation 488/2014) residue in the finished product,
  deliberately per-product-type since darker/higher-cocoa-content
  products carry higher regulatory action levels than lighter ones.
  `viscosity-max-pa-s` is the maximum allowable Casson viscosity
  (Pa·s) for the molding-line pour -- too viscous risks incomplete
  mold fill and air-bubble entrapment."
  {:chocolate/dark
   {:id :chocolate/dark
    :name "ダークチョコレート"
    :moisture-target-percent 1.0
    :moisture-tolerance-percent 0.3
    :cocoa-content-min-percent 35.0
    :particle-size-max-microns 25
    :process-temp-min-c 31.0
    :process-temp-max-c 32.0
    :cadmium-max-ppm 0.8
    :viscosity-max-pa-s 5.0}

   :chocolate/milk
   {:id :chocolate/milk
    :name "ミルクチョコレート"
    :moisture-target-percent 1.0
    :moisture-tolerance-percent 0.3
    :cocoa-content-min-percent 25.0
    :particle-size-max-microns 25
    :process-temp-min-c 29.0
    :process-temp-max-c 30.0
    :cadmium-max-ppm 0.3
    :viscosity-max-pa-s 4.0}

   :chocolate/white
   {:id :chocolate/white
    :name "ホワイトチョコレート"
    ;; :cocoa-content-min-percent represents the minimum COCOA-BUTTER
    ;; percentage for white chocolate (Codex STAN 87-1981 sec 2.1.4) --
    ;; white chocolate has no non-fat cocoa solids by definition.
    :moisture-target-percent 0.8
    :moisture-tolerance-percent 0.2
    :cocoa-content-min-percent 20.0
    :particle-size-max-microns 25
    :process-temp-min-c 28.0
    :process-temp-max-c 29.0
    :cadmium-max-ppm 0.1
    :viscosity-max-pa-s 4.0}

   :confectionery/hard-candy
   {:id :confectionery/hard-candy
    :name "砂糖菓子(ハードキャンディ)"
    ;; :cocoa-content-min-percent 0.0 -- no cocoa in pure sugar
    ;; confectionery, this check never fires for this product type.
    :moisture-target-percent 2.0
    :moisture-tolerance-percent 0.5
    :cocoa-content-min-percent 0.0
    :particle-size-max-microns 150
    ;; boiled-sugar "hard crack" cook-temperature window
    :process-temp-min-c 145.0
    :process-temp-max-c 155.0
    :cadmium-max-ppm 0.05
    :viscosity-max-pa-s 8.0}})

(defn product-type-by-id [id]
  (get product-types id))

(def jurisdictions
  "Cocoa/chocolate/sugar-confectionery manufacturing jurisdictions and
  their evidence-checklist requirements."
  {:jp/mhlw
   {:id :jp/mhlw
    :name "日本 (食品表示法・厚生労働省)"
    :required-evidence
    [:cocoa-bean-intake-record
     :roasting-conching-log
     :moisture-test
     :cocoa-content-test
     :particle-size-test
     :tempering-temp-log
     :cadmium-test
     :allergen-declaration
     :weight-check]}

   :us/fda
   {:id :us/fda
    :name "United States (FDA/FALCPA)"
    :required-evidence
    [:cocoa-bean-intake-record
     :roasting-conching-log
     :moisture-test
     :cocoa-content-test
     :particle-size-test
     :tempering-temp-log
     :cadmium-test
     :allergen-declaration
     :weight-check]}

   :eu/efsa
   {:id :eu/efsa
    :name "European Union (EFSA / Regulation (EC) 488/2014)"
    :required-evidence
    [:cocoa-bean-intake-record
     :roasting-conching-log
     :moisture-test
     :cocoa-content-test
     :particle-size-test
     :tempering-temp-log
     :cadmium-test
     :allergen-declaration
     :weight-check]}})

(defn jurisdiction-by-id [id]
  (get jurisdictions id))

(defn required-evidence-satisfied?
  "Verify that every item in the jurisdiction's `:required-evidence` list
  is present in `evidence`. `jurisdiction` may be a resolved jurisdiction
  map (as returned by `jurisdiction-by-id`) or a raw jurisdiction id --
  both call conventions are in use (tests pass a resolved map; the
  Governor passes the raw id straight off batch metadata)."
  [jurisdiction evidence]
  (let [j (if (map? jurisdiction) jurisdiction (jurisdiction-by-id jurisdiction))]
    (if-not j
      false
      (set/subset? (set (:required-evidence j)) (set evidence)))))

(defn moisture-in-range?
  "Positive-sense convenience predicate: does `percent` fall within
  `product`'s moisture tolerance window (inclusive) around its target?
  Chocolate outside its moisture window risks sugar bloom/viscosity
  faults (too high) or brittleness/handling problems (too low); boiled
  sugar confectionery outside range risks stickiness/graining."
  [percent product]
  (boolean
   (and (some? product)
        (let [target (:moisture-target-percent product)
              tol (:moisture-tolerance-percent product)]
          (and (>= percent (- target tol))
               (<= percent (+ target tol)))))))

(defn cocoa-content-meets-minimum?
  "Positive-sense convenience predicate: does `percent` meet or exceed
  `product`'s minimum required cocoa content (or cocoa-butter content
  for white chocolate)?"
  [percent product]
  (boolean
   (and (some? product)
        (>= percent (:cocoa-content-min-percent product)))))

(defn particle-size-within-max?
  "Positive-sense convenience predicate: does `microns` stay at or below
  `product`'s maximum allowable particle size (refining fineness)?"
  [microns product]
  (boolean
   (and (some? product)
        (<= microns (:particle-size-max-microns product)))))

(defn process-temp-in-range?
  "Positive-sense convenience predicate: does `celsius` fall within
  `product`'s tempering/cook temperature window (inclusive)?"
  [celsius product]
  (boolean
   (and (some? product)
        (>= celsius (:process-temp-min-c product))
        (<= celsius (:process-temp-max-c product)))))

(defn cadmium-within-max?
  "Positive-sense convenience predicate: does `ppm` stay at or below
  `product`'s maximum allowable cadmium residue?"
  [ppm product]
  (boolean
   (and (some? product)
        (<= ppm (:cadmium-max-ppm product)))))

(defn viscosity-within-max?
  "Positive-sense convenience predicate: does `pa-s` stay at or below
  `product`'s maximum allowable molding-line viscosity?"
  [pa-s product]
  (boolean
   (and (some? product)
        (<= pa-s (:viscosity-max-pa-s product)))))
