(ns kenbun.http
  "The network surface, as a pure function: `(handle ctx req) -> resp`.

  No I/O, no host JSON, no transport. The deploy shell owns authentication,
  the socket, and the store it injects — the same seam
  `kotobase.protocols.issue` uses, so kenbun mounts alongside it rather than
  bringing its own server.

  Wire format is EDN (`pr-str` / `clojure.edn/read-string`,
  `application/edn`), matching that surface: nothing external forces JSON
  here, and the data is Clojure-shaped with namespaced keys all the way down
  to the store.

  ## The reporter is taken from the context, never from the body

  This is the one rule the surface exists to enforce. `kenbun.credit` turns
  an admitted finding into units owed to a named reporter, so a body-supplied
  reporter id is an instruction to pay whoever the submitter names. Every
  handler here derives the reporter from `ctx`'s authenticated principal, and
  a body that carries a `:reporter` is REJECTED rather than ignored — a
  silently dropped field looks identical to an honoured one from the client's
  side, and the client should learn it is not how identity works.

  Authenticating the principal is the shell's job; trusting it is not this
  namespace's decision to make. `handle` refuses every write with no
  principal rather than inventing an anonymous one.

  ## Portability

  This namespace is `.cljc` and runs wherever the injected store does. Note
  that `kenbun.store.kotobase` is JVM-only (kotobase.core returns Promises on
  ClojureScript while `IssueStore` is synchronous), so a Worker deployment
  currently has `store/mem-store` and nothing durable. The handler is not
  what blocks that."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [kenbun.finding :as finding]
            [kenbun.intake :as intake]
            [kenbun.triage :as triage]
            [kotoba.issue.store :as store]))

(def edn-content-type "application/edn")

(defn- resp [status body]
  {:status status
   :headers {"content-type" edn-content-type}
   :body (pr-str body)})

(defn- error [status reason detail]
  (resp status (cond-> {:kenbun.error/reason reason}
                 detail (assoc :kenbun.error/detail detail))))

(defn- segments [path]
  (vec (remove str/blank? (str/split (or path "") #"/"))))

(defn- parse-body
  "Returns the parsed body, or `::malformed`. `nil` is a legitimate body, so
  it cannot double as the failure value."
  [req]
  (let [b (:body req)]
    (cond
      (nil? b) nil
      (map? b) b
      :else (try (edn/read-string b)
                 (catch #?(:clj Throwable :cljs :default) _ ::malformed)))))

(defn- query-param [req k]
  (get (:query-params req) k))

;; ---------- principal ----------

(defn- principal->reporter
  "The authenticated principal, as a finding reporter. The shell decides who
  the principal is; the shape it must supply is `{:id ... :kind ...}` with
  kind in `finding/reporter-kinds`, plus `:model` for an agent."
  [{:keys [id kind model prompt-cid]}]
  (cond-> {:id id :kind kind}
    model (assoc :model model)
    prompt-cid (assoc :prompt-cid prompt-cid)))

(defn- valid-principal? [p]
  (and (map? p)
       (some? (:id p))
       (contains? finding/reporter-kinds (:kind p))))

;; ---------- handlers ----------

(defn- submit-finding [{:keys [store principal now]} body]
  (cond
    (contains? body :reporter)
    (error 400 :reporter-not-accepted-in-body
           {:note "the reporter is the authenticated principal; remove :reporter"})

    (contains? body :reproduced-by)
    (error 400 :reproduced-by-not-accepted-in-body
           {:note "corroboration is recorded by submitting the same finding, not by claiming it"})

    :else
    (let [submission (-> body
                         (assoc :reporter (principal->reporter principal))
                         (cond-> now (assoc :submitted-at now)))
          result (intake/submit! store submission)]
      ;; The outcome, not an HTTP-shaped guess at it: :rejected and
      ;; :undecidable are different answers and stay different here. 422
      ;; ("understood, will not process") for a claim that was examined and
      ;; refused; 409 for one that could not be judged, which is the server
      ;; declining to pretend either way.
      (resp (case (:kenbun.intake/outcome result)
              :filed 201
              :corroborated 200
              :rejected 422
              :undecidable 409)
            result))))

(defn- get-finding [{:keys [store]} id]
  (if-let [f (store/get-entity store :finding id)]
    (resp 200 f)
    (error 404 :no-such-finding {:id id})))

(defn- list-findings [{:keys [store]} req]
  (let [lane (some-> (query-param req "lane") keyword)
        confirmed (query-param req "confirmed")
        pred (fn [f]
               (and (or (nil? lane)
                        (= lane (:kenbun.triage/lane (triage/triage f))))
                    (or (nil? confirmed)
                        (= (= "true" confirmed) (boolean (:kenbun.finding/confirmed? f))))))]
    (resp 200 {:kenbun.list/findings (store/list-entities store :finding pred)})))

(defn- list-issues [{:keys [store]} req]
  (let [state (some-> (query-param req "state") keyword)
        lane (some-> (query-param req "lane") keyword)
        pred (fn [i]
               (and (or (nil? state) (= state (:kotoba.issue/state i)))
                    (or (nil? lane) (= lane (:kotoba.issue/lane i)))))]
    (resp 200 {:kenbun.list/issues (store/list-entities store :issue pred)})))

(defn- review-proposal [{:keys [store principal]} proposal-id body]
  (let [verdict (:verdict body)]
    (cond
      (not (contains? #{:approve :reject :request-changes} verdict))
      (error 400 :unknown-verdict {:verdict verdict})

      (nil? (store/get-entity store :proposal proposal-id))
      (error 404 :no-such-proposal {:id proposal-id})

      :else
      (try
        (resp 200 (intake/review! store proposal-id verdict
                                  {:decider (:id principal) :note (:note body)}))
        (catch #?(:clj Throwable :cljs :default) t
          ;; gate/review! throws on an already-terminal proposal. That is a
          ;; conflict, not a server fault, and the message is kept rather
          ;; than replaced with a generic string.
          (error 409 :proposal-not-reviewable {:message (ex-message t)}))))))

(defn- report [{:keys [store]}]
  (let [findings (store/list-entities store :finding nil)
        issues (store/list-entities store :issue nil)]
    (resp 200
          {:kenbun.report/findings-on-file (count findings)
           :kenbun.report/issues-open (count (filter #(= :open (:kotoba.issue/state %)) issues))
           :kenbun.report/issues-triaged (count (filter #(= :triaged (:kotoba.issue/state %)) issues))
           :kenbun.report/confirmed (count (filter :kenbun.finding/confirmed? findings))
           ;; Rejected and undecidable submissions leave no entity behind --
           ;; nothing is written for them -- so this report deliberately does
           ;; NOT claim a submission total. Reporting only what is on file as
           ;; if it were everything submitted is the same collapse the
           ;; admission gate refuses; `intake/intake-report` over a batch of
           ;; results is what answers that, and it needs the results.
           :kenbun.report/note "counts entities on file; submissions refused or undecided are not stored and are not counted here"})))

;; ---------- routing ----------

(def ^:private write-methods #{:post :put :patch :delete})

(defn handle
  "ctx: `{:store <IssueStore> :principal {:id :kind :model?} :now?}`.
  req: `{:method :path :body? :query-params?}`.

  Routes:

    POST /findings                    submit a finding
    GET  /findings                    list (?lane= ?confirmed=)
    GET  /findings/{id}               one finding
    GET  /issues                      list (?state= ?lane=)
    POST /proposals/{id}/reviews      record a verdict; :approve realizes it
    GET  /report                      counts of what is on file"
  [ctx req]
  (let [segs (segments (:path req))
        method (:method req)
        n (count segs)
        body (parse-body req)]
    (cond
      (= ::malformed body)
      (error 400 :malformed-edn-body nil)

      ;; Every write needs an authenticated principal. Refusing here rather
      ;; than defaulting to an anonymous reporter is the point: credit is
      ;; owed to a name.
      (and (contains? write-methods method) (not (valid-principal? (:principal ctx))))
      (error 401 :no-authenticated-principal
             {:required "{:id <string> :kind :human|:agent}"})

      (and (= 1 n) (= "findings" (first segs)) (= :post method))
      (submit-finding ctx (or body {}))

      (and (= 1 n) (= "findings" (first segs)) (= :get method))
      (list-findings ctx req)

      (and (= 2 n) (= "findings" (first segs)) (= :get method))
      (get-finding ctx (second segs))

      (and (= 1 n) (= "issues" (first segs)) (= :get method))
      (list-issues ctx req)

      (and (= 3 n) (= "proposals" (first segs)) (= "reviews" (nth segs 2)) (= :post method))
      (review-proposal ctx (second segs) (or body {}))

      (and (= 1 n) (= "report" (first segs)) (= :get method))
      (report ctx)

      :else
      (error 404 :no-such-route {:method method :path (:path req)}))))
