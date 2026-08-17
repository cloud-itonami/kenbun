(ns kenbun.probe
  "Ask the real kotobase.net what its datom plane does, instead of assuming.

  One question decides the store adapter: **when the same attribute is
  transacted twice for one entity, does the second value REPLACE the first or
  ACCUMULATE beside it?**

  `IssueStore/put-entity!` is specified to merge, so a proposal moving
  :proposed -> :approved -> :merged must read back as :merged. The embedded
  engine (`kotobase.core` over `storage.memory`) accumulates — measured, and
  it is why `kenbun.store.kotobase` retracts before it asserts. Whether the
  hosted service behaves the same way is a property of its schema, not of any
  source file here, so it gets measured too.

  Run with an EPHEMERAL identity and a throwaway database name, exactly as
  `kotobase.live-e2e` does. This deliberately does NOT use the cloud-itonami
  fleet seed: the question is about server behaviour, and answering it must
  not put kenbun's data under a second DID — a new DID is a new graph, which
  is the sharding the owner ruled out on 2026-07-30.

    npm run probe"
  (:require ["@noble/curves/ed25519.js" :refer [ed25519]]
            [clojure.string :as str]
            [kotobase.client :as client]))

(def endpoint "https://kotobase.net")
(def operator-did "did:web:kotobase.net")

(defn- log [& xs] (js/console.log (str/join " " (map str xs))))

(defn- rows [^js result]
  (js->clj (or (.-rows_edn result) (.-rows result) #js [])))

(defn- settle
  "kotobase.net is not read-your-writes immediately; live-e2e polls. Poll for
  a predicate rather than sleeping a guessed amount."
  [c db-name query pred remaining]
  (-> (client/q c db-name query)
      (.then (fn [result]
               (cond
                 (pred result) result
                 (pos? remaining)
                 (-> (js/Promise. (fn [res] (js/setTimeout res 700)))
                     (.then (fn [_] (settle c db-name query pred (dec remaining)))))
                 :else result)))))

(defn main []
  (let [secret (.randomPrivateKey (.-utils ed25519))
        db-name (str "kenbun-probe-" (.now js/Date))
        eid (str "kenbun-probe/p-1")
        c (client/make-client {:endpoint endpoint
                               :operator-did operator-did
                               :secret-key secret})
        q "{:find [?v] :where [[?e :kenbun.probe/status ?v]]}"]
    (log "probe db:" db-name)
    (log "probe did:" (:did c))
    (-> (client/transact c db-name (pr-str [{:db/id eid :kenbun.probe/status "proposed"}])
                         {:retry? true})
        (.then (fn [^js r]
                 (log "first transact ok; graph =" (.-graph r))
                 (settle c db-name q #(seq (rows %)) 12)))
        (.then (fn [^js r]
                 (log "after 1st write, rows =" (pr-str (rows r)))
                 (client/transact c db-name
                                  (pr-str [{:db/id eid :kenbun.probe/status "approved"}])
                                  {:retry? true})))
        (.then (fn [_]
                 (settle c db-name q
                         (fn [res] (some #(re-find #"approved" (str %)) (rows res)))
                         12)))
        (.then (fn [^js r]
                 (let [rs (rows r)
                       n (count rs)]
                   (log "after 2nd write, rows =" (pr-str rs))
                   (log "")
                   (log "ANSWER:"
                        (cond
                          (and (= 1 n) (re-find #"approved" (str rs)))
                          "REPLACES — [:db/add e a v] is cardinality-one here; the adapter does NOT need retract-before-assert"

                          (and (> n 1) (re-find #"proposed" (str rs)))
                          "ACCUMULATES — same as the embedded engine; the adapter MUST retract before asserting"

                          :else
                          (str "UNDECIDED — " n " row(s), neither shape matched; do not guess, read the rows above")))
                   (log "")
                   (log "rows returned:" n))))
        ;; Second question, same class: the adapter's fix depends on retract
        ;; working over XRPC. The embedded engine accepts [:db/retract e a v]
        ;; and ignores a nil wildcard; neither is guaranteed here.
        (.then (fn [_]
                 (log "")
                 (log "--- retract probe ---")
                 (client/transact c db-name
                                  (pr-str [[:db/retract eid :kenbun.probe/status "proposed"]])
                                  {:retry? true})))
        (.then (fn [_]
                 (settle c db-name q
                         (fn [res] (not (some #(re-find #"proposed" (str %)) (rows res))))
                         12)))
        (.then (fn [^js r]
                 (let [rs (rows r)]
                   (log "after retract, rows =" (pr-str rs))
                   (log "RETRACT:"
                        (cond
                          (and (= 1 (count rs)) (re-find #"approved" (str rs)))
                          "WORKS — [:db/retract e a v] removes exactly that value"
                          (some #(re-find #"proposed" (str %)) rs)
                          "NO-OP — retract did not remove the value; the adapter's fix does not apply here"
                          :else
                          "UNDECIDED — read the rows above, do not guess")))))
        (.catch (fn [e]
                  (js/console.error "probe failed:" (or (.-message e) e))
                  (set! (.-exitCode js/process) 1))))))
