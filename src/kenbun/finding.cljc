(ns kenbun.finding
  "検分 — a *finding*: the thing a reporter submits before anything is believed.

  A finding is a CLAIM, not a fact. It carries who is claiming it (a human or
  an agent), what was under test, what defect is asserted, and the evidence
  offered for the assertion. Nothing here decides whether the claim is any
  good — that is `kenbun.evidence`'s job, and it applies the same bar to both
  reporter kinds.

  The reporter kind is recorded, never used to lower or raise the admission
  bar. It changes only what is *kept*: an agent's finding also records the
  model and the prompt it came from, so a later audit can ask which model
  produced which unreproducible claims."
  (:require [clojure.string :as str]))

(def reporter-kinds
  "Who can file. Both go through the identical admission gate."
  #{:human :agent})

(def severities
  "Ordered least → most severe. `severity-rank` is the only place the order
  is encoded; nothing else may compare severities by keyword name."
  [:trivial :low :medium :high :critical])

(def ^:private severity->rank
  (into {} (map-indexed (fn [i s] [s i]) severities)))

(defn severity-rank
  "Rank of `s`, or nil when `s` is not a known severity. Returning nil rather
  than a default is deliberate: an unknown severity must make the finding
  *undecidable*, not quietly sort as :trivial."
  [s]
  (get severity->rank s))

(defn known-severity? [s] (some? (severity-rank s)))

(defn- blank-str? [x]
  (or (nil? x) (and (string? x) (str/blank? x))))

(defn- trim-or-nil [x]
  (when-not (blank-str? x) (str/trim (str x))))

(defn reporter
  "A reporter. `:kind` must be in `reporter-kinds`; an unknown kind is kept
  as-is so `kenbun.evidence` can report it as undecidable rather than this
  constructor throwing and losing the submission."
  [{:keys [id kind model prompt-cid]}]
  (cond-> {:kenbun.reporter/id id
           :kenbun.reporter/kind kind}
    model (assoc :kenbun.reporter/model model)
    prompt-cid (assoc :kenbun.reporter/prompt-cid prompt-cid)))

(defn evidence
  "The evidence bundle offered for a claim.

  `steps` are the reproduction steps. `expected` and `observed` are the two
  halves of the discrimination — a finding whose expected and observed sides
  are absent or identical has not stated a defect at all, however long its
  prose. `artifact-cid` addresses a stored artifact (log, screenshot, trace)
  in the content-addressed plane; it is corroboration, never a substitute for
  the expected/observed pair."
  [{:keys [steps expected observed artifact-cid tooling]}]
  (cond-> {:kenbun.evidence/steps (vec (keep trim-or-nil steps))}
    (trim-or-nil expected) (assoc :kenbun.evidence/expected (trim-or-nil expected))
    (trim-or-nil observed) (assoc :kenbun.evidence/observed (trim-or-nil observed))
    artifact-cid (assoc :kenbun.evidence/artifact-cid artifact-cid)
    tooling (assoc :kenbun.evidence/tooling tooling)))

(defn target
  "What was under test. `rev` pins the finding to a revision — without it a
  later reader cannot tell whether a still-open issue was already fixed."
  [{:keys [repo rev surface]}]
  (cond-> {:kenbun.target/repo repo}
    rev (assoc :kenbun.target/rev rev)
    surface (assoc :kenbun.target/surface surface)))

(defn finding
  "Assemble a finding. Sub-maps already in namespaced form are passed
  through, so a finding read back out of a store round-trips."
  [{:keys [id title severity summary submitted-at reproduced-by]
    rep :reporter tgt :target ev :evidence
    :as m}]
  (if (:kenbun.finding/id m)
    m
    (cond-> {:kenbun.finding/id id
             :kenbun.finding/title title
             :kenbun.finding/severity severity
             :kenbun.finding/reporter (if (:kenbun.reporter/id rep) rep (reporter rep))
             :kenbun.finding/target (if (:kenbun.target/repo tgt) tgt (target tgt))
             ;; nil evidence stays nil — an absent bundle and an empty bundle
             ;; must stay distinguishable, since `kenbun.evidence` reports
             ;; them as different rejection reasons.
             :kenbun.finding/evidence (when (some? ev)
                                        (if (contains? ev :kenbun.evidence/steps)
                                          ev
                                          (evidence ev)))
             ;; Other reporters who independently reproduced this. Set, not
             ;; count: the same reporter confirming twice is one witness.
             :kenbun.finding/reproduced-by (set reproduced-by)}
      (trim-or-nil summary) (assoc :kenbun.finding/summary (trim-or-nil summary))
      submitted-at (assoc :kenbun.finding/submitted-at submitted-at))))

(defn agent-reported? [f]
  (= :agent (get-in f [:kenbun.finding/reporter :kenbun.reporter/kind])))

(defn independent-witnesses
  "How many reporters other than the original filer reproduced this."
  [f]
  (count (disj (set (:kenbun.finding/reproduced-by f))
               (get-in f [:kenbun.finding/reporter :kenbun.reporter/id]))))
