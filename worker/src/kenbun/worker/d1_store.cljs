(ns kenbun.worker.d1-store
  "Durable persistence for the Worker, as hydrate -> run -> commit.

  `kotoba.issue.store/IssueStore` is synchronous and `kenbun.http/handle` is
  a pure function over it. D1 is asynchronous. Rather than make the contract
  async — which would fork every namespace in this repo into a promise-shaped
  twin — the request loads the entities into an in-memory store, runs the
  pure handler against that seed, and writes back only what changed. This is
  the shape `kotobase-protocols-worker` proved for the same problem
  (ADR-2607177500): hydrate!, run the existing synchronous sequence, commit
  the diff.

  ## Why a Durable Object sits in front

  Read-modify-write over a shared table loses updates when two requests
  interleave, and the loss is silent: both return 201 and one finding is
  gone. The workspace's rule is to use a Durable Object as the serializer and
  keep storage in a shared backend rather than in the object — so one named
  DO instance owns every write and D1 holds the rows. Being globally unique
  and single-threaded, the DO supplies 'there is exactly one writer' without
  a write lease or fencing epoch written here.

  ## Every external object is `^js` tagged

  Not style. Under `:advanced`, Closure renames properties it cannot infer a
  type for: the first version of this namespace compiled `(.-DB env)` to
  `env.Xy`, which is `undefined` at runtime — a Worker that finds no database
  and no bindings, reporting nothing wrong until a request arrives. The
  `^js` hints are what stop the renaming, and the build must stay free of
  `:infer-warning` for that reason.

  ## What this forecloses, said plainly

  These rows are NOT in the shared kotobase datom plane. A query joining
  kenbun findings to `repo-taxonomy` or `repo-maturity` on repo path — 'which
  repositories carry the most confirmed defects' — cannot be written against
  this deployment.

  That is a consequence of THIS backend, not a property of Workers. An
  earlier version of this paragraph said moving to the datom plane needed an
  async IssueStore contract that did not exist upstream; that was wrong and
  is retracted. `kotoba-lang/kotobase-client` is ClojureScript, kotobase.net
  is live, and the hydrate/commit bridge above is backend-agnostic — what
  blocks the move is key custody (itonami-fleet-kotobase-seed), not any
  missing contract. D1 is therefore provisional.

  D1 is used as an app database, not as a premise for anything claiming to be
  distributed: delete it and kenbun loses its issue history, which is exactly
  why kenbun is not on a path that makes decentralisation claims."
  (:require [clojure.edn :as edn]
            [goog.object]
            [kotoba.issue.store :as store]))

(def schema
  "One row per entity, plus an append-only audit table. `body` is EDN, the
  same encoding the HTTP surface speaks, so nothing re-encodes on the way
  through."
  ["CREATE TABLE IF NOT EXISTS entity (
      kind TEXT NOT NULL,
      id   TEXT NOT NULL,
      body TEXT NOT NULL,
      PRIMARY KEY (kind, id))"
   "CREATE TABLE IF NOT EXISTS audit (
      seq  INTEGER PRIMARY KEY AUTOINCREMENT,
      id   TEXT NOT NULL,
      body TEXT NOT NULL)"])

(defn ensure-schema! [^js db]
  (.batch db (clj->js (mapv (fn [s] (.prepare db s)) schema))))

(defn- rows [^js result]
  (js->clj (.-results result) :keywordize-keys true))

(def hydrate-ceiling
  "The most entities a request will load before refusing to serve.

  `hydrate!` loads everything, because dedupe asks 'is any finding on file the
  same defect as this one', which is a question about all of them. That is a
  v0.1 choice with a real limit, and the limit is enforced rather than
  described: past this count a request REFUSES, naming the number, instead of
  loading anyway and failing somewhere inside the Worker's CPU budget with an
  error about something else.

  A service that quietly degrades past its own ceiling is the same failure
  this repository exists to catch — a limit nobody can see reached is a limit
  discovered by its consequences. Raising this number is not the fix; loading
  candidates by fingerprint is, and that waits on which backend stays."
  5000)

(defn ceiling-for
  "The effective ceiling. `HYDRATE_CEILING` overrides the default so the limit
  can be exercised on a live deployment — a bound that has never been seen to
  trigger is a bound nobody knows works."
  [env]
  (let [v (goog.object/get env "HYDRATE_CEILING")]
    (if (and v (re-matches #"\d+" (str v))) (js/parseInt v 10) hydrate-ceiling)))

(defn entity-count [^js db]
  (-> (.first (.prepare db "SELECT count(*) AS n FROM entity"))
      (.then (fn [row] (if row (.-n row) 0)))))

(defn hydrate!
  "Load every entity into a fresh `mem-store`, or reject if there are more
  than the effective ceiling.

  The rejection is a thrown error carrying the count, so the caller reports
  the real reason rather than a generic store failure."
  [^js db ceiling]
  (-> (entity-count db)
      (.then (fn [n]
               (when (> n ceiling)
                 (throw (ex-info "hydrate ceiling exceeded"
                                 {:kenbun.error/reason :hydrate-ceiling-exceeded
                                  :entities n
                                  :ceiling ceiling})))
               (.all (.prepare db "SELECT kind, id, body FROM entity"))))
      (.then (fn [result]
               (let [s (store/mem-store)]
                 (doseq [{:keys [kind id body]} (rows result)]
                   (store/put-entity! s (keyword kind) id (edn/read-string body)))
                 s)))))

(defn- entity-snapshot
  "Every entity in a mem-store, as {[kind id] entity}."
  [s]
  (into {}
        (for [[kind entities] (:entities @(:state s))
              [id entity] entities]
          [[kind id] entity])))

(defn commit!
  "Write back the entities that differ from the seed, and append the new
  audit records. Untouched rows are not rewritten."
  [^js db before after audit-before audit-after]
  (let [changed (for [[k entity] after
                      :when (not= entity (get before k))]
                  [k entity])
        new-audit (drop (count audit-before) audit-after)
        stmts (concat
               (for [[[kind id] entity] changed]
                 (.bind (.prepare db "INSERT INTO entity (kind, id, body) VALUES (?, ?, ?)
                                      ON CONFLICT(kind, id) DO UPDATE SET body = excluded.body")
                        (name kind) id (pr-str entity)))
               (for [a new-audit]
                 (.bind (.prepare db "INSERT INTO audit (id, body) VALUES (?, ?)")
                        (str (:kotoba.issue.audit/id a)) (pr-str a))))]
    (if (seq stmts)
      ;; One batch: D1 runs it as a transaction, so a request cannot leave
      ;; half of its writes behind.
      (-> (.batch db (clj->js (vec stmts)))
          (.then (fn [_] {:entities (count changed) :audit (count new-audit)})))
      (js/Promise.resolve {:entities 0 :audit 0}))))

(defn with-store
  "hydrate -> f(store) -> commit. `f` is synchronous and pure apart from the
  store it is handed. Returns a promise of `[result commit-stats]`."
  [^js db ceiling f]
  (-> (hydrate! db ceiling)
      (.then (fn [s]
               (let [before (entity-snapshot s)
                     audit-before (vec (store/audit-log s))
                     result (f s)
                     after (entity-snapshot s)
                     audit-after (vec (store/audit-log s))]
                 (-> (commit! db before after audit-before audit-after)
                     (.then (fn [stats] #js [result (clj->js stats)]))))))))
