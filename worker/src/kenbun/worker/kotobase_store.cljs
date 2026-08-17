(ns kenbun.worker.kotobase-store
  "Persistence over kotobase.net — the workspace's graph BaaS — with the same
  hydrate -> run -> commit shape `d1-store` uses.

  This is the backend kenbun should have had from the start. `d1-store` exists
  because an earlier reading concluded the Worker could not reach the datom
  plane, on the grounds that `kotobase.core` returns Promises on ClojureScript
  while `IssueStore` is synchronous. That reasoning was wrong twice over:
  `kotoba-lang/kotobase-client` is ClojureScript and talks to the hosted
  service over XRPC, and the synchronous/asynchronous mismatch is the very
  thing the hydrate/commit bridge already solved.

  Putting kenbun here is what makes its findings joinable with the rest of the
  fleet's graph — `:apex` binds graph scope to the issuer DID, so the number
  of keys IS the Datalog join range, which is why ~1,197 cloud-itonami actors
  share one seed instead of sharding into refs nobody can query across.

  ## Two properties measured against the live service, not assumed

  `kenbun.probe` asked kotobase.net directly (ephemeral identity, throwaway
  database), because neither answer is readable from source:

  1. **A repeated attribute ACCUMULATES.** Writing `:status` twice leaves both
     values — `[[\"proposed\"] [\"approved\"]]` — exactly as the embedded engine
     does. `put-entity!` is specified to merge, so every write here retracts
     the existing values for that attribute before asserting the new one.
  2. **`[:db/retract e a v]` works**, removing exactly that value. The fix in
     (1) therefore applies; it was not safe to assume it would.

  ## Values are `pr-str`-encoded

  The same encoding `kenbun.store.kotobase` uses, for the same reason: it
  keeps `Date`, sets, maps and keywords round-tripping, and it means both
  adapters behave identically rather than each having its own edge cases. The
  probe confirmed strings round-trip as strings, so an encoded value is a
  value this plane already handles.

  ## Consistency

  kotobase.net is not read-your-writes on the instant — `kotobase.live-e2e`
  polls for its own marker, and so does the probe. A commit here therefore
  does NOT wait for its own write to become visible: the Durable Object in
  front is what makes the next request see it, because that request hydrates
  after this one's transact has been acknowledged."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [kotobase.client :as client]
            [kotoba.issue.store :as store]))

(def default-db-name
  "One database, therefore one graph, therefore one Datalog reach. Splitting
  findings from the issues they opened would make 'which reporter's finding
  became a merged fix' unanswerable."
  "kenbun")

(defn db-name-of
  "The database this client writes to. Injected rather than global so the e2e
  check can point at a throwaway database without mutating a var the
  deployment also reads."
  [c]
  (or (:kenbun/db-name c) default-db-name))

(def ^:private kind-attr :kenbun.store/kind)
(def ^:private id-attr :kenbun.store/id)
(def ^:private seq-attr :kenbun.store/seq)
(def ^:private reserved #{kind-attr id-attr seq-attr})

(defn encode [v] (pr-str v))
(defn decode [s] (edn/read-string s))

(defn- entity-id [kind id] (str "kenbun:" (name kind) ":" id))

(defn make-client
  "A write client for the operator seed. `nil` when no seed is configured,
  which the caller must treat as 'this backend is not available' rather than
  as an empty database."
  [secret-key-hex]
  (when (and secret-key-hex (seq secret-key-hex))
    (let [bytes (js/Uint8Array.from
                 (map (fn [i] (js/parseInt (subs secret-key-hex (* 2 i) (+ 2 (* 2 i))) 16))
                      (range (/ (count secret-key-hex) 2))))]
      (client/make-client {:endpoint "https://kotobase.net"
                           :operator-did "did:web:kotobase.net"
                           :secret-key bytes}))))

(defn- datom-rows [^js result]
  (js->clj (or (.-datoms result) (.-rows result) #js []) :keywordize-keys true))

(defn- attr->key
  "A datom's attribute comes back as a STRING carrying its leading colon —
  \":kenbun.store/kind\", not \"kenbun.store/kind\". `(keyword \":x\")` produces
  `::x`, which matches nothing, so every entity silently failed to rebuild
  while its writes were landing correctly. Measured by the live e2e; nothing
  in the source says which form the wire uses."
  [a]
  (let [s (str a)]
    (keyword (if (str/starts-with? s ":") (subs s 1) s))))

(defn hydrate!
  "Load every kenbun entity into a fresh `mem-store`.

  Uses an `:eavt` index scan rather than a Datalog query: the question is
  'give me every fact in this database', and a scan says that directly."
  [c]
  (-> (client/datoms c (db-name-of c) "eavt" nil)
      (.then (fn [result]
               (let [s (store/mem-store)]
                 (doseq [[eid facts] (group-by :e (datom-rows result))]
                   ;; TWO decodes. `v_edn` is the EDN text OF THE STORED
                   ;; VALUE, and the stored value is the string this adapter
                   ;; wrote with `encode`. One decode yields that string back;
                   ;; the second yields the value. Measured — with a single
                   ;; decode every entity failed to rebuild while its writes
                   ;; were landing correctly, which read as "the store does
                   ;; not persist" rather than "the store cannot parse".
                   (let [attrs (into {} (map (fn [{:keys [a v_edn v]}]
                                               [(attr->key a) (decode (decode (or v_edn v)))]))
                                     facts)
                         kind (get attrs kind-attr)
                         id (get attrs id-attr)]
                     (when (and kind id)
                       (store/put-entity! s kind id (apply dissoc attrs reserved)))))
                 ;; The raw side keeps values ONE decode in — the exact string
                 ;; this adapter wrote — because that is what a retract has to
                 ;; name and what a change comparison has to match.
                 [s (into {} (map (fn [[eid facts]]
                                    [eid (into {} (map (juxt (comp attr->key :a)
                                                             (fn [{:keys [v_edn v]}]
                                                               (decode (or v_edn v)))))
                                               facts)]))
                          (group-by :e (datom-rows result)))])))))

(defn- entity-snapshot [s]
  (into {}
        (for [[kind entities] (:entities @(:state s))
              [id entity] entities]
          [[kind id] entity])))

(defn commit!
  "Transact the difference. For every changed attribute, retract the value
  currently on file before asserting the new one — measured as necessary,
  since this plane accumulates."
  [c before after raw-facts extra-tx]
  (let [tx (reduce
            (fn [acc [[kind id] entity]]
              (if (= entity (get before [kind id]))
                acc
                (let [eid (entity-id kind id)
                      existing (get raw-facts eid {})
                      new? (empty? existing)
                      base (when new?
                             [{:db/id eid kind-attr (encode kind) id-attr (encode id)}])]
                  (into (into acc base)
                        (mapcat (fn [[k v]]
                                  (let [prior (get existing k)]
                                    (cond-> []
                                      (and prior (not= prior (encode v)))
                                      (conj [:db/retract eid k prior])
                                      true
                                      (conj {:db/id eid k (encode v)})))))
                        entity))))
            []
            after)
        tx (into (vec tx) extra-tx)]
    (if (seq tx)
      (-> (client/transact c (db-name-of c) (pr-str tx) {:retry? true})
          (.then (fn [_] {:tx (count tx)})))
      (js/Promise.resolve {:tx 0}))))

(defn- audit-tx
  "New audit records as entities of kind :audit.

  The audit log is NOT part of `entity-snapshot`, so a commit that only wrote
  changed entities would drop every record — the trail would vanish while
  every other test still passed. Each record gets a sequence seeded from the
  highest already stored, which is the same defect the JVM adapter hit when
  its counter restarted on reopen: here every request is a fresh process, so
  seeding from storage is not an optimisation but the only correct source."
  [raw-facts audit-before audit-after]
  (let [stored-max (->> (vals raw-facts)
                        (keep #(get % seq-attr))
                        (map decode)
                        (filter number?)
                        (reduce max 0))]
    (map-indexed
     (fn [i a]
       (let [n (+ stored-max i 1)
             eid (entity-id :audit (str n "-" (:kotoba.issue.audit/id a)))]
         (into {:db/id eid
                kind-attr (encode :audit)
                id-attr (encode (:kotoba.issue.audit/id a))
                seq-attr (encode n)}
               (map (fn [[k v]] [k (encode v)]))
               a)))
     (drop (count audit-before) audit-after))))

(defn with-store
  "hydrate -> f(store) -> commit, matching `d1-store/with-store` so the Worker
  can hold either backend without knowing which."
  [c _ceiling f]
  (-> (hydrate! c)
      ;; `hydrate!` returns a ClojureScript vector, so it is destructured, not
       ;; `aget`-ed. aget on a cljs vector yields undefined and the failure
       ;; surfaces far away as "No protocol method IssueStore.list-entities
       ;; defined for type undefined".
      (.then (fn [[s raw]]
               (let [_ nil
                     before (entity-snapshot s)
                     audit-before (vec (store/audit-log s))
                     result (f s)
                     after (entity-snapshot s)
                     audit-after (vec (store/audit-log s))]
                 (-> (commit! c before after raw (audit-tx raw audit-before audit-after))
                     (.then (fn [stats] #js [result (clj->js stats)]))))))))
