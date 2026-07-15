(ns chocops.phase
  "Phase machine: the states a cocoa/chocolate/sugar-confectionery
  production batch transits through.

  State machine:
    :intake -> :roasting -> :conching -> :tempering -> :molding ->
    :package -> :audit -> :archived

  `:intake` is cocoa-bean/sugar/raw-material receiving; `:roasting` is
  cocoa-bean roasting and winnowing (nib separation) for the
  cocoa/chocolate route, or the initial cook stage for the sugar-
  confectionery route; `:conching` is extended mechanical working of
  the chocolate mass to smooth texture and drive off volatile
  off-flavors -- never directly controlled by this actor, conching-line
  control remains exclusive to plant staff; `:tempering` is the
  crystallization stage that sets the polymorphic cocoa-butter crystal
  form (Form V) that prevents fat bloom, or the boil/cook-and-cool
  stage for sugar confectionery -- never directly controlled by this
  actor, tempering/molding-line control remains exclusive to plant
  staff; `:molding` is forming into final shapes (bars/pieces/
  confectionery) -- never directly controlled by this actor; `:package`
  is finished-product packaging; `:audit` is compliance audit;
  `:archived` is the terminal state.

  Each transition can accept a proposal and yield an audit fact.")

(def all-phases
  "All valid phases in the cocoa/chocolate/sugar-confectionery
  production workflow."
  [:intake :roasting :conching :tempering :molding :package :audit :archived])

(def phase-sequence
  "Ordered phases representing normal batch progression."
  [:intake :roasting :conching :tempering :molding :package :audit :archived])

(defn valid-phase?
  "Check if a phase is valid."
  [phase]
  (contains? (set all-phases) phase))

(defn- index-of
  "Portable (Clojure/ClojureScript) index lookup -- `.indexOf` is a
  JVM-only `java.util.List` method that ClojureScript's PersistentVector
  does not implement, so it is avoided here even though `phase-sequence`
  is a plain vector. Returns -1 when `x` is not found, matching
  `java.util.List/indexOf`'s contract."
  [coll x]
  (or (first (keep-indexed (fn [i v] (when (= v x) i)) coll)) -1))

(defn can-transition?
  "Check if a transition from one phase to another is valid
  (must be forward-only in the sequence, no backtracking). Always returns a
  boolean (never nil), including when either phase is invalid."
  [from-phase to-phase]
  (boolean
   (and (valid-phase? from-phase) (valid-phase? to-phase)
        (let [from-idx (index-of phase-sequence from-phase)
              to-idx (index-of phase-sequence to-phase)]
          (and (>= from-idx 0) (>= to-idx 0) (< from-idx to-idx))))))
