(ns kenbun.inspect
  "Print exactly what kotobase.net returns, so the store stops being written
  against a guessed wire shape."
  (:require ["@noble/curves/ed25519.js" :refer [ed25519]]
            [kotobase.client :as client]))

(defn- try-decode [s]
  (try (cljs.reader/read-string s) (catch :default e (str "<throw " (.-message e) ">"))))

(defn main []
  (let [secret (.randomPrivateKey (.-utils ed25519))
        db (str "kenbun-inspect-" (.now js/Date))
        c (client/make-client {:endpoint "https://kotobase.net"
                               :operator-did "did:web:kotobase.net"
                               :secret-key secret})
        vals {:t/keyword :finding :t/string "hello" :t/long 42
              :t/set #{"a" "b"} :t/map {:k "v"} :t/nil nil}
        ;; send exactly what the store sends: pr-str of each value
        payload (into {:db/id "kenbun:probe:v"} (map (fn [[k v]] [k (pr-str v)])) vals)]
    (js/console.log "db:" db)
    (-> (client/transact c db (pr-str [payload]) {:retry? true})
        (.then (fn [_] (js/Promise. (fn [r] (js/setTimeout r 3000)))))
        (.then (fn [_] (client/datoms c db "eavt" nil)))
        (.then (fn [^js res]
                 (doseq [d (js->clj (.-datoms res) :keywordize-keys true)]
                   (let [ve (:v_edn d)
                         once (try-decode ve)
                         twice (if (string? once) (try-decode once) "<n/a>")]
                     (js/console.log
                      (str (:a d)
                           "\n    v_edn raw   = " (pr-str ve)
                           "\n    decode x1   = " (pr-str once)
                           "\n    decode x2   = " (pr-str twice)))))))
        (.catch (fn [e] (js/console.error "inspect failed:" (or (.-message e) e))
                  (set! (.-exitCode js/process) 1))))))
