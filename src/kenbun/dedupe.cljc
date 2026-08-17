(ns kenbun.dedupe
  "Two reporters describing one defect in different words are one issue with
  two witnesses, not two issues.

  The fingerprint is taken over the *discriminating* content — target repo,
  surface, and the expected/observed pair — never over the title or summary,
  which is where the wording differs most and carries the least information.

  The key is kept as a full string and compared exactly; the short hash is
  only an index handle. A hash collision therefore cannot merge two distinct
  findings, which matters more here than a compact key: merging two real
  defects makes one of them disappear silently, and this repository exists to
  stop things disappearing silently."
  (:require [clojure.string :as str]))

(defn- norm
  "Lowercase and collapse whitespace. Digits and punctuation are preserved:
  'returns 404' and 'returns 500' are different defects."
  [x]
  (if (nil? x)
    ""
    (str/lower-case (str/join " " (remove str/blank? (str/split (str x) #"\s+"))))))

(defn key-of
  "The exact dedupe key. Fields are joined with a separator that cannot occur
  in a normalized field, so ('a', 'bc') and ('ab', 'c') cannot collide."
  [f]
  (let [t (:kenbun.finding/target f)
        ev (:kenbun.finding/evidence f)]
    (str/join "␟"
              [(norm (:kenbun.target/repo t))
               (norm (:kenbun.target/surface t))
               (norm (:kenbun.evidence/expected ev))
               (norm (:kenbun.evidence/observed ev))])))

(defn- fnv1a-32
  "FNV-1a over UTF-16 code units. Written out rather than using `hash` because
  `hash` is not guaranteed equal across Clojure and ClojureScript, and this
  handle is stored — a fingerprint that changes with the runtime would split
  an issue's history in half."
  [s]
  (let [prime 16777619
        mask 0xFFFFFFFF]
    (loop [i 0 h 2166136261]
      (if (>= i (count s))
        (bit-and h mask)
        (recur (inc i)
               (bit-and (* (bit-xor h (bit-and (int (nth s i)) 0xFFFF)) prime) mask))))))

(defn fingerprint
  "Short display/index handle for `key-of`. Never used alone for equality."
  [f]
  (let [h (fnv1a-32 (key-of f))]
    (str "kb-" (str/join "" (map #(nth "0123456789abcdef" (bit-and (bit-shift-right h %) 15))
                                 [28 24 20 16 12 8 4 0])))))

(defn duplicate-of
  "The first finding in `known` whose exact key equals `f`'s, or nil.

  `known` is supplied by the caller (typically a store query narrowed by
  fingerprint) — this namespace does no I/O and holds no index."
  [f known]
  (let [k (key-of f)]
    (first (filter #(= k (key-of %)) known))))

(defn merge-witness
  "Fold a duplicate submission into the finding already on file: the new
  reporter becomes a witness, and the higher of the two claimed severities
  wins only when the duplicate carries its own evidence.

  Severity is deliberately NOT raised by an unevidenced restatement — that is
  how a bounty-farmed 'me too' inflates a trivial finding into a critical one."
  [original duplicate severity-rank]
  (let [rep-id (get-in duplicate [:kenbun.finding/reporter :kenbun.reporter/id])
        evidenced? (some? (:kenbun.finding/evidence duplicate))
        a (severity-rank (:kenbun.finding/severity original))
        b (severity-rank (:kenbun.finding/severity duplicate))]
    (cond-> original
      rep-id (update :kenbun.finding/reproduced-by (fnil conj #{}) rep-id)
      (and evidenced? a b (> b a))
      (assoc :kenbun.finding/severity (:kenbun.finding/severity duplicate)))))
