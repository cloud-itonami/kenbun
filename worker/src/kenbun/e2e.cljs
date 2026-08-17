(ns kenbun.e2e
  "End-to-end check of `kenbun.worker.kotobase-store` against the REAL
  kotobase.net, with an ephemeral identity and a throwaway database.

  Everything here is the thing that would otherwise be assumed: that a store
  written against a measured plane actually round-trips a finding, that
  dedupe still works when the candidates have to come back over the network,
  that a status transition reads back as the latest value rather than one of
  the accumulated ones, and that the audit trail survives at all.

  The ephemeral identity is deliberate. It answers 'does this code work'
  without putting kenbun's data under a second DID — a new DID is a new
  graph, which is the sharding the owner ruled out on 2026-07-30. The real
  deployment points at the fleet seed; only the seed differs.

    npm run e2e"
  (:require ["@noble/curves/ed25519.js" :refer [ed25519]]
            [clojure.string :as str]
            [kenbun.intake :as intake]
            [kenbun.worker.kotobase-store :as kbs]
            [kotobase.client :as client]
            [kotoba.issue.store :as store]))

(defonce failures (atom []))

(defn- check! [label ok? detail]
  (if ok?
    (js/console.log "  PASS " label)
    (do (swap! failures conj label)
        (js/console.log "  FAIL " label "  ->" (pr-str detail)))))

(defn- log [& xs] (js/console.log (str/join " " (map str xs))))

(defn- submission [overrides]
  (merge {:id "f-1"
          :title "Signup form returns 500 on valid input"
          :severity :medium
          :reporter {:id "hana" :kind :human}
          :target {:repo "net-kotobase/site" :rev "abc1234" :surface "/signup"}
          :evidence {:steps ["open /signup" "submit a valid address"]
                     :expected "201 and a confirmation mail"
                     :observed "500, no mail"}}
         overrides))

(defn- settle
  "kotobase.net is not read-your-writes on the instant. Poll a fresh hydrate
  until `pred` holds, rather than sleeping a guessed amount and hoping."
  [c pred remaining]
  (-> (kbs/hydrate! c)
      (.then (fn [[s _]]
               (let []
                 (cond
                   (pred s) (js/Promise.resolve s)
                   (pos? remaining)
                   (-> (js/Promise. (fn [res] (js/setTimeout res 900)))
                       (.then (fn [_] (settle c pred (dec remaining)))))
                   :else (js/Promise.resolve s)))))))

(defn main []
  (let [secret (.randomPrivateKey (.-utils ed25519))
        db (str "kenbun-e2e-" (.now js/Date))
        ;; A throwaway database per run, injected into the client, so a rerun
        ;; never inherits the previous run's state and passes for the wrong
        ;; reason — and so nothing here mutates a var the deployment reads.
        c (assoc (client/make-client {:endpoint "https://kotobase.net"
                                      :operator-did "did:web:kotobase.net"
                                      :secret-key secret})
                 :kenbun/db-name db)]
    (log "e2e db :" db)
    (log "e2e did:" (:did c))
    (log "")

    (log "1. hydrate on an empty graph")
    (-> (kbs/hydrate! c)
        (.then (fn [[s _]]
                 (let []
                   (check! "empty graph yields an empty store"
                           (empty? (store/list-entities s :finding nil))
                           (store/list-entities s :finding nil)))

                 (log "2. file a finding through the real store")
                 (kbs/with-store c nil (fn [s] (intake/submit! s (submission {}))))))

        (.then (fn [pair]
                 (let [r (aget pair 0)]
                   (check! "submit! reports :filed"
                           (= :filed (:kenbun.intake/outcome r))
                           (:kenbun.intake/outcome r)))
                 (log "3. a LATER hydrate sees what the commit wrote")
                 (settle c #(seq (store/list-entities % :finding nil)) 15)))

        (.then (fn [s]
                 (let [f (store/get-entity s :finding "f-1")]
                   (check! "the finding came back" (some? f) f)
                   (check! "its title survived"
                           (= "Signup form returns 500 on valid input" (:kenbun.finding/title f))
                           (:kenbun.finding/title f))
                   (check! "its severity survived as a keyword"
                           (= :medium (:kenbun.finding/severity f))
                           (:kenbun.finding/severity f))
                   (check! "nested evidence survived"
                           (= ["open /signup" "submit a valid address"]
                              (get-in f [:kenbun.finding/evidence :kenbun.evidence/steps]))
                           (get-in f [:kenbun.finding/evidence :kenbun.evidence/steps]))
                   (check! "the issue was opened"
                           (= 1 (count (store/list-entities s :issue nil)))
                           (count (store/list-entities s :issue nil))))

                 (log "4. corroboration, with candidates coming off the plane")
                 (kbs/with-store c nil
                   (fn [s] (intake/submit! s (submission {:id "f-2"
                                                          :title "cannot sign up at all"
                                                          :reporter {:id "agent-7" :kind :agent}}))))))

        (.then (fn [pair]
                 (let [r (aget pair 0)]
                   (check! "second report is :corroborated, not a new filing"
                           (= :corroborated (:kenbun.intake/outcome r))
                           (:kenbun.intake/outcome r))
                   (check! "the agent is recorded as a witness"
                           (= #{"agent-7"} (get-in r [:kenbun.intake/finding
                                                      :kenbun.finding/reproduced-by]))
                           (get-in r [:kenbun.intake/finding :kenbun.finding/reproduced-by])))

                 (log "5. a status transition reads back as the LATEST value")
                 (kbs/with-store c nil
                   (fn [s]
                     (store/put-entity! s :proposal "p-x" {:kotoba.issue.proposal/status :proposed})
                     :ok))))

        (.then (fn [_]
                 (settle c #(some? (store/get-entity % :proposal "p-x")) 15)))

        (.then (fn [_]
                 (kbs/with-store c nil
                   (fn [s]
                     (store/put-entity! s :proposal "p-x" {:kotoba.issue.proposal/status :merged})
                     :ok))))

        (.then (fn [_]
                 (settle c #(= :merged (:kotoba.issue.proposal/status
                                        (store/get-entity % :proposal "p-x")))
                         15)))

        (.then (fn [s]
                 (let [status (:kotoba.issue.proposal/status (store/get-entity s :proposal "p-x"))]
                   (check! "the plane accumulates, but the store reads back :merged"
                           (= :merged status) status))

                 (log "6. the audit trail persisted")
                 (client/datoms c db "eavt" nil)))

        (.then (fn [^js result]
                 (let [rows (js->clj (or (.-datoms result) (.-rows result) #js [])
                                     :keywordize-keys true)
                       audit-kinds (->> rows
                                        (filter #(= ":kenbun.store/kind" (str (:a %))))
                                        (filter #(re-find #"audit" (str (or (:v_edn %) (:v %))))))]
                   (check! "audit records are on the plane"
                           (pos? (count audit-kinds))
                           (count audit-kinds))
                   (check! "issue counts are exactly one defect"
                           (= 1 (->> rows
                                     (filter #(= ":kenbun.store/kind" (str (:a %))))
                                     (filter #(re-find #"issue" (str (or (:v_edn %) (:v %)))))
                                     count))
                           :see-rows))

                 (log "")
                 (if (seq @failures)
                   (do (js/console.error "E2E FAILED:" (count @failures) "check(s):" (pr-str @failures))
                       (set! (.-exitCode js/process) 1))
                   (log "E2E PASSED — all checks green against live kotobase.net"))))

        (.catch (fn [e]
                  (js/console.error "e2e threw:" (or (.-message e) e))
                  (set! (.-exitCode js/process) 1))))))
