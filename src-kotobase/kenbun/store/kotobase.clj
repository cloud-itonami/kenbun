(ns kenbun.store.kotobase
  "A durable `kotoba.issue.store/IssueStore` over the kotobase datom plane.

  Opt-in: this namespace lives on `src-kotobase`, not `src`, so the base
  library keeps `kotoba-issue` as its only dependency. Enable it with the
  `:kotobase` alias.

  Everything kenbun writes — findings, issues, proposals, reviews, and the
  audit log — goes into ONE kotobase database handle, and therefore one ref.
  That is deliberate: `kotobase.core/open` takes a single `:ref-name`, so
  Datalog reach is exactly one ref (superproject ADR-260726). Splitting
  findings from the issues they opened would make 'which reporter's findings
  became merged fixes' unanswerable by query. The cost is that all kenbun
  writes serialize through one ref, which is the trade this workspace's rule
  says to state rather than take silently.

  ## Three things the plane does that a naive adapter gets wrong

  Each was measured against `kotobase.storage.memory`, not assumed:

  1. **`transact!` accumulates; it does not replace.** Writing `status`
     twice leaves BOTH triples, and `q` returns a set — so a proposal that
     went :proposed -> :approved -> :merged reads back as an arbitrary one of
     the three. `IssueStore/put-entity!` is specified to MERGE, so every write
     here retracts the existing values for that predicate first, in the same
     transaction as the assert.

  2. **Values are stringified on the way in, and `Date` is stringified with
     `str`, not `pr-str`.** A raw `java.util.Date` comes back as
     \"Thu Jan 01 09:00:00 JST 1970\" — unparseable, and every
     `kotoba.issue.gate` audit record carries one. So values are encoded with
     `pr-str` and decoded with `clojure.edn/read-string`, which round-trips
     `#inst` along with sets, maps, vectors, keywords, and numbers.

  3. **`nil` becomes the empty string**, which a genuinely empty string also
     becomes. Uniform `pr-str` removes the ambiguity: `nil` encodes to
     \"nil\" and \"\" encodes to \"\\\"\\\"\".

  Encoding every value uniformly costs something real: an external Datalog
  query must match the encoded form (`\":critical\"`, not `:critical`). That
  is a visible cost — the query returns nothing — rather than a silent one,
  which is why it is preferred over encoding only some types. `decode` is
  public so external callers can read what kenbun wrote.

  ## Not yet

  `kotobase.core` on ClojureScript returns Promises, and `IssueStore` is a
  synchronous protocol. This adapter is therefore JVM-only. A Worker
  deployment needs an async issue-store contract, which does not exist here
  or upstream; that is a real gap, not an oversight."
  (:require [clojure.edn :as edn]
            [kotobase.core :as kb]
            [kotoba.issue.store :as store]))

(def kind-predicate
  "Marks a subject as belonging to an entity partition, so `list-entities`
  can find subjects without scanning every triple in the ref."
  "kenbun.store/kind")

(def id-predicate "kenbun.store/id")
(def seq-predicate "kenbun.store/seq")

(def ^:private reserved #{kind-predicate id-predicate seq-predicate})

(defn encode
  "Value -> the string stored as a triple's object. `pr-str` rather than
  `str`, so `Date` becomes a readable `#inst` instead of a locale-formatted
  sentence that no reader accepts."
  [v]
  (pr-str v))

(defn decode
  "The inverse of `encode`. Public: an external query against this ref reads
  encoded objects, and needs this to get values back."
  [s]
  (edn/read-string s))

(defn- attr->predicate
  "Qualified keyword -> bare string, matching the workspace's datom-plane
  convention of colon-less attribute names."
  [k]
  (subs (str k) 1))

(defn- predicate->attr [p] (keyword p))

(defn- subject [kind id] (str "kenbun:" (name kind) ":" id))

(defn- triples-for [db s]
  (kb/q db [s nil nil]))

(defn- entity-from-triples
  "Rebuild an entity map, dropping the adapter's own bookkeeping predicates.

  If a predicate somehow carries more than one value — which this adapter's
  own writes cannot produce, but a foreign writer into the same ref can — the
  entity is NOT silently resolved to one of them. Guessing here would
  reintroduce exactly the bug this adapter exists to avoid."
  [ts]
  (let [by-pred (group-by :p ts)]
    (reduce-kv
     (fn [m p vs]
       (if (contains? reserved p)
         m
         (let [values (distinct (map :o vs))]
           (if (= 1 (count values))
             (assoc m (predicate->attr p) (decode (first values)))
             (throw (ex-info "Predicate carries multiple values; refusing to pick one"
                             {:predicate p :values (mapv decode values)}))))))
     {}
     by-pred)))

(defn- put-tx
  "Retract-then-assert for each predicate in `m`, as one transaction.

  Only predicates present in `m` are touched, because `put-entity!` merges
  rather than replaces the whole entity."
  [s m existing]
  (let [existing-by-pred (group-by :p existing)]
    (into []
          (mapcat (fn [[k v]]
                    (let [p (attr->predicate k)
                          retracts (map (fn [t] [:db/retract s p (:o t)])
                                        (get existing-by-pred p))]
                      ;; Wildcard retract ([:db/retract s p nil]) was measured
                      ;; to be a no-op, so each existing value is named.
                      (concat retracts [[s p (encode v)]]))))
          m)))

(defrecord KotobaseIssueStore [db audit-counter]
  store/IssueStore
  (get-entity [_ kind id]
    (let [ts (triples-for db (subject kind id))]
      (when (seq ts)
        (entity-from-triples ts))))

  (put-entity! [_ kind id m]
    (let [s (subject kind id)
          existing (triples-for db s)
          bookkeeping (when (empty? existing)
                        [[s kind-predicate (encode kind)]
                         [s id-predicate (encode id)]])]
      (kb/transact! db (into (vec bookkeeping) (put-tx s m existing)))
      (entity-from-triples (triples-for db s))))

  (list-entities [_ kind pred]
    (let [subjects (map :s (kb/q db [nil kind-predicate (encode kind)]))
          entities (keep (fn [s]
                           (let [ts (triples-for db s)]
                             (when (seq ts) (entity-from-triples ts))))
                         subjects)]
      (vec (if pred (filter pred entities) entities))))

  (append-audit! [_ audit-map]
    ;; Append-only: the subject carries a monotonic sequence rather than the
    ;; audit id alone. kotoba.issue.gate derives audit ids from the entity
    ;; they describe, so two records CAN share one (a proposal reviewed, then
    ;; reviewed again with the same verdict). Keying on the id would let the
    ;; second write silently overwrite the first, which is the one thing an
    ;; audit log may not do.
    (let [n (swap! audit-counter inc)
          s (subject :audit (str n "-" (:kotoba.issue.audit/id audit-map)))]
      (kb/transact! db (into [[s kind-predicate (encode :audit)]
                              [s id-predicate (encode (:kotoba.issue.audit/id audit-map))]
                              [s seq-predicate (encode n)]]
                             (map (fn [[k v]] [s (attr->predicate k) (encode v)]))
                             audit-map))
      audit-map)))

(defn kotobase-store
  "Wrap an open kotobase database as an `IssueStore`.

  The caller opens the database, which keeps provider choice (memory, sqlite,
  s3, postgres) and crypto policy out of here entirely — this namespace never
  names a storage backend."
  [db]
  (->KotobaseIssueStore db (atom 0)))

(defn audit-log
  "The audit trail in append order. `mem-store`'s `audit-log` returns
  insertion order from a vector; here the order is recovered from the
  sequence written alongside each record, because a triple store has no
  inherent order and reporting an arbitrary one as 'the audit trail' would be
  worse than not offering the function."
  [^KotobaseIssueStore s]
  (let [db (:db s)
        subjects (map :s (kb/q db [nil kind-predicate (encode :audit)]))]
    (->> subjects
         (map (fn [subj]
                (let [ts (triples-for db subj)
                      n (some #(when (= seq-predicate (:p %)) (decode (:o %))) ts)]
                  [n (entity-from-triples ts)])))
         (sort-by first)
         (mapv second))))
