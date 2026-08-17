(ns kenbun.worker
  "Cloudflare Worker deploy shell for `kenbun.http`.

  The shell owns exactly the three things the pure surface refuses to own:
  the socket, authentication, and the store. `kenbun.http/handle` is
  unchanged here and unaware it is on a network.

  ## Every request goes through one Durable Object

  Reads included. A read that bypasses the writer can observe a
  half-committed batch; routing everything through one named object costs a
  hop and removes the question. Being globally unique and single-threaded,
  the object supplies 'there is exactly one writer' without a write lease or
  fencing epoch written here — the workspace's rule is to use a DO as the
  serializer and keep the rows in a shared backend, which is what D1 is for
  in `kenbun.worker.d1-store`.

  ## Authentication

  `Authorization: Bearer <token>` is looked up in the `PRINCIPALS` secret, an
  EDN map of token -> principal. Absent or unknown tokens yield no principal,
  and `kenbun.http/handle` refuses writes without one — the shell does not
  invent an anonymous reporter, because credit is owed to a name. A malformed
  secret yields nil rather than throwing, so a misconfiguration degrades to
  'nobody can write' rather than 'anybody can'.

  The principal is resolved HERE and handed to the object as a header. The
  object is not publicly addressable, so that header can only originate
  above; a client cannot set it. This is the rule the HTTP surface enforces
  against request bodies, applied to the hop between shell and object.

  A shared-secret table is the weakest identity this could have. It is one
  function with one input so CACAO verification can replace it without
  touching anything else, and the limitation is written down rather than
  implied by silence.

  ## `^js` and `goog.object/get`

  Under `:advanced`, Closure renames properties whose target type it cannot
  infer. The first version of this namespace compiled `env.PRINCIPALS` to
  `env.rc` and `env.KENBUN_STORE` to `env.qc` — both `undefined` at runtime,
  giving a Worker that finds no bindings and no principals and reports
  nothing wrong until a request arrives. Binding lookups therefore go through
  `goog.object/get` with a string, and every external object is `^js` tagged."
  (:require [clojure.edn :as edn]
            [goog.object :as gobj]
            [kenbun.http :as http]
            [kenbun.worker.d1-store :as d1]))

(def principal-header "x-kenbun-principal")

(defn- binding-of
  "A Worker binding by its literal name. Never `(.-NAME env)`: that is the
  form Closure renames."
  [env k]
  (gobj/get env k))

(defn- bearer-token [^js request]
  (let [auth (some-> (.-headers request) (.get "authorization"))]
    (when-let [m (and auth (re-find #"(?i)^bearer\s+(.+)$" auth))]
      (second m))))

(defn principal-for
  "Bearer token -> principal, or nil. nil is a real answer: it means the
  request is unauthenticated, and every write path treats it as such."
  [^js request env]
  (let [token (bearer-token request)
        table (try (edn/read-string (or (binding-of env "PRINCIPALS") "{}"))
                   (catch :default _ nil))]
    (when (and token (map? table))
      (get table token))))

(defn- query-params [^js url]
  (let [out (volatile! {})]
    (.forEach (.-searchParams url) (fn [v k] (vswap! out assoc k v)))
    @out))

(defn- ->response [{:keys [status headers body]}]
  (js/Response. body #js {:status status :headers (clj->js headers)}))

(defn- edn-response [status m]
  (js/Response. (pr-str m)
                #js {:status status
                     :headers #js {"content-type" "application/edn"}}))

;; ---------- the Durable Object ----------

(defn handle-in-object
  "Runs inside the DO: ensure schema, hydrate, run the pure handler, commit
  the diff."
  [env ^js request]
  (let [url (js/URL. (.-url request))
        db (binding-of env "DB")]
    (-> (.text request)
        (.then
         (fn [body]
           (let [principal (try (edn/read-string
                                 (or (.get (.-headers request) principal-header) "nil"))
                                (catch :default _ nil))
                 req {:method (keyword (.toLowerCase (.-method request)))
                      :path (.-pathname url)
                      :query-params (query-params url)
                      :body (when-not (= "" body) body)}]
             (-> (d1/ensure-schema! db)
                 (.then (fn [_]
                          (d1/with-store db (fn [store]
                                              (http/handle {:store store :principal principal}
                                                           req)))))
                 (.then (fn [pair] (->response (aget pair 0))))))))
        (.catch (fn [e]
                  ;; The message is kept. A generic 500 would make a schema
                  ;; problem and a bug in the handler look identical, and 503
                  ;; says the store failed rather than that the request was
                  ;; wrong.
                  (edn-response 503 {:kenbun.error/reason :store-failure
                                     :kenbun.error/detail {:message (str (.-message e))}}))))))

(deftype KenbunStore [ctx env]
  Object
  (fetch [_ request] (handle-in-object env request)))

;; ---------- the Worker entry ----------

(defn- health [env]
  (edn-response 200
                {:kenbun/service "kenbun"
                 :kenbun/surface "kenbun.http"
                 :kenbun/store "d1 behind one durable object"
                 ;; Whether writes are possible at all is a fact about the
                 ;; deployment, and a service that can accept nobody should
                 ;; say so on its own health endpoint rather than let the
                 ;; first reporter discover it.
                 :kenbun/principals-configured
                 (boolean (seq (try (edn/read-string (or (binding-of env "PRINCIPALS") "{}"))
                                    (catch :default _ nil))))
                 :kenbun/bindings-present
                 (vec (remove nil? [(when (binding-of env "DB") "DB")
                                    (when (binding-of env "KENBUN_STORE") "KENBUN_STORE")]))}))

(defn fetch-handler [^js request env _ctx]
  (let [url (js/URL. (.-url request))
        method (.-method request)
        bodyless? (contains? #{"GET" "HEAD"} method)]
    (if (= "/health" (.-pathname url))
      (js/Promise.resolve (health env))
      (-> (if bodyless? (js/Promise.resolve "") (.text request))
          (.then
           (fn [body]
             (let [principal (principal-for request env)
                   headers (js/Headers.)
                   ns* (binding-of env "KENBUN_STORE")]
               (.set headers "content-type" "application/edn")
               (.set headers principal-header (pr-str principal))
               (let [id (.idFromName ^js ns* "kenbun")
                     stub (.get ^js ns* id)]
                 (.fetch ^js stub
                         (js/Request. (.-url request)
                                      (if bodyless?
                                        #js {:method method :headers headers}
                                        #js {:method method :headers headers :body body})))))))))))

(def handler #js {:fetch fetch-handler})
