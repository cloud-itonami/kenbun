(ns kenbun.credit
  "What a reporter earned, as an integer.

  This namespace computes credit and **moves nothing**. It has no payment
  rail, no ledger write, no transfer — the number it returns is an input to
  whatever settlement plane a deployment chooses, and that plane is where
  authority to move value lives. Keeping the arithmetic here and the movement
  elsewhere is what makes the arithmetic auditable: it is a pure function of
  the finding, so two parties can recompute it and compare.

  Credit is only ever computed for an ADMITTED finding. Rejected and
  undecidable findings earn nothing and are not passed here — an undecidable
  finding earning zero would be indistinguishable from an admitted trivial
  one that also happened to earn zero."
  (:require [kenbun.finding :as finding]))

(def base-by-severity
  "Base credit units. Deliberately coarse and deliberately flat at the top:
  the jump from :high to :critical is small because the incentive to inflate
  a claim scales with that gap, and inflation is what the admission gate
  spends its effort catching."
  {:trivial 1 :low 3 :medium 10 :high 30 :critical 50})

(def first-reporter-share
  "The filer of a defect gets full base. Corroborators get this fraction,
  expressed as a numerator over `share-denominator` so the arithmetic stays
  integral and identical in Clojure and ClojureScript."
  1)

(def share-denominator 4)

;; `quot` rather than any Math/ interop: every value here is a non-negative
;; integer, so truncation is floor division, and `quot` means the same thing
;; in Clojure and ClojureScript. `long` does not exist in cljs.
(defn- floor-div [n d] (quot n d))

(defn credit
  "Credit for `reporter-id` on an admitted finding.

  Three cases, and they are kept separate rather than folded into one
  formula so each stays readable:

    original filer, corroborated    base + one corroboration bonus
    original filer, uncorroborated  base
    corroborator                    a share of base

  The bonus is paid once no matter how many witnesses arrive: a finding
  reproduced by twenty agents is not twenty times more valuable than one
  reproduced by one, and paying per-witness is an invitation to run twenty
  agents."
  [f reporter-id]
  (let [base (get base-by-severity (:kenbun.finding/severity f))
        filer (get-in f [:kenbun.finding/reporter :kenbun.reporter/id])
        witnesses (finding/independent-witnesses f)
        corroborator? (contains? (disj (set (:kenbun.finding/reproduced-by f)) filer)
                                 reporter-id)]
    (cond
      (nil? base) 0
      (= reporter-id filer) (if (pos? witnesses)
                              (+ base (floor-div base share-denominator))
                              base)
      corroborator? (max 1 (* first-reporter-share (floor-div base share-denominator)))
      :else 0)))

(defn split
  "Credit for every reporter attached to a finding, as {reporter-id units}.
  The total is not fixed — corroboration creates value rather than dividing
  the filer's, because a scheme that pays the corroborator out of the filer's
  share teaches filers to suppress corroboration."
  [f]
  (let [filer (get-in f [:kenbun.finding/reporter :kenbun.reporter/id])
        ids (cond-> (disj (set (:kenbun.finding/reproduced-by f)) filer)
              filer (conj filer))]
    (into {} (map (fn [id] [id (credit f id)])) ids)))
