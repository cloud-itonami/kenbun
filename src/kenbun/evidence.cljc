(ns kenbun.evidence
  "Admission: may this claim become an issue?

  This is the namespace the whole service exists for. A bug tracker that
  accepts whatever is filed accumulates unverified claims the same way a test
  suite that cannot run accumulates green checkmarks — and for the same
  reason: **the failure and the success return the same value.** So admission
  here is three-valued and stays three-valued all the way out:

    :admitted     — evidence discriminates; this is a defect report
    :rejected     — evidence was examined and does not support the claim
    :undecidable  — the check could not be run at all (unknown severity,
                    unknown reporter kind). NOT a pass, NOT a rejection.

  Collapsing `:undecidable` into either of the other two is the bug this
  namespace is built to avoid, so `admit-batch` reports all three counts and
  refuses to answer with a single boolean.

  The bar does not depend on who is filing. An agent's confident prose and a
  human's confident prose are both prose; what is checked is whether an
  expected/observed pair and reproduction steps are actually present, and
  whether the offered evidence contradicts the severity being claimed."
  (:require [clojure.string :as str]
            [kenbun.finding :as finding]))

(def ^:private hedges
  "Language that concedes the defect was not actually reproduced. A finding
  may legitimately contain these words at low severity — 'intermittent' is a
  real property of a real bug. What is rejected is claiming HIGH severity
  while the evidence itself says the reporter could not make it happen."
  #"(?i)could not reproduce|couldn't reproduce|cannot reproduce|can't reproduce|not reproducible|unable to reproduce|did not verify|didn't verify|not verified|untested|unconfirmed|i (?:think|believe|assume)|probably|possibly|might be|may be caused|appears to|seems to|likely a")

(def contradiction-severity-floor
  "At or above this rank, hedged evidence contradicts the claim. Below it,
  a hedge is an honest confidence statement and is allowed through."
  (finding/severity-rank :high))

(defn- hedged? [s] (boolean (and s (re-find hedges (str s)))))

(defn- normalized-blank? [s]
  (or (nil? s) (str/blank? (str s))))

(defn- same-text?
  "Whether two sides of a discrimination say the same thing modulo case and
  whitespace. Punctuation and digits are NOT normalized away: 'returns 404'
  and 'returns 500' must stay different."
  [a b]
  (letfn [(norm [x] (str/lower-case (str/join " " (remove str/blank? (str/split (str x) #"\s+")))))]
    (= (norm a) (norm b))))

(defn- undecidable [reason detail]
  (cond-> {:kenbun.admission/verdict :undecidable
           :kenbun.admission/reason reason}
    detail (assoc :kenbun.admission/detail detail)))

(defn- rejected [reason detail]
  (cond-> {:kenbun.admission/verdict :rejected
           :kenbun.admission/reason reason}
    detail (assoc :kenbun.admission/detail detail)))

(defn admit
  "Decide a single finding. Always returns a map with
  `:kenbun.admission/verdict` in #{:admitted :rejected :undecidable} and a
  `:kenbun.admission/reason` — the reason survives on every path, including
  the admitted one, so a caller logging only the verdict still cannot claim a
  check ran that did not."
  [f]
  (let [severity (:kenbun.finding/severity f)
        kind (get-in f [:kenbun.finding/reporter :kenbun.reporter/kind])
        ev (:kenbun.finding/evidence f)
        steps (:kenbun.evidence/steps ev)
        expected (:kenbun.evidence/expected ev)
        observed (:kenbun.evidence/observed ev)]
    (cond
      ;; ---- undecidable: the check itself could not run ----
      (not (finding/known-severity? severity))
      (undecidable :unknown-severity {:severity severity})

      (not (contains? finding/reporter-kinds kind))
      (undecidable :unknown-reporter-kind {:kind kind})

      (normalized-blank? (:kenbun.finding/title f))
      (undecidable :no-title nil)

      ;; ---- rejected: examined, and it does not hold up ----
      (nil? ev)
      (rejected :no-evidence nil)

      (empty? steps)
      (rejected :no-repro-steps nil)

      (or (normalized-blank? expected) (normalized-blank? observed))
      (rejected :no-discrimination
                {:has-expected (not (normalized-blank? expected))
                 :has-observed (not (normalized-blank? observed))})

      (same-text? expected observed)
      (rejected :expected-equals-observed {:expected expected})

      (and (>= (finding/severity-rank severity) contradiction-severity-floor)
           (some hedged? [expected observed (:kenbun.finding/summary f)]))
      (rejected :evidence-contradicts-severity {:severity severity})

      :else
      {:kenbun.admission/verdict :admitted
       :kenbun.admission/reason :discriminating-evidence
       :kenbun.admission/witnesses (finding/independent-witnesses f)})))

(defn admitted? [decision]
  (= :admitted (:kenbun.admission/verdict decision)))

(defn admit-batch
  "Decide a collection of findings.

  Returns per-finding decisions plus a `:kenbun.admission/scanned` floor and
  the three counts. There is deliberately no `:clean?` or `:ok?` key: a batch
  where nothing could be decided must not be answerable with the same value
  as a batch where everything passed. A caller that wants one number has to
  choose which of the three it means."
  [findings]
  (let [decisions (mapv (fn [f] [(:kenbun.finding/id f) (admit f)]) findings)
        by-verdict (frequencies (map (comp :kenbun.admission/verdict second) decisions))]
    {:kenbun.admission/scanned (count decisions)
     :kenbun.admission/decisions decisions
     :kenbun.admission/admitted (get by-verdict :admitted 0)
     :kenbun.admission/rejected (get by-verdict :rejected 0)
     :kenbun.admission/undecidable (get by-verdict :undecidable 0)}))
