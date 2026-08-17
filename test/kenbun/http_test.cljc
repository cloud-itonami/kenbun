(ns kenbun.http-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [kenbun.http :as http]
            [kotoba.issue.store :as store]))

(defn- fresh-ctx
  ([] (fresh-ctx {:id "hana" :kind :human}))
  ([principal] {:store (store/mem-store) :principal principal}))

(defn- body-of [resp] (edn/read-string (:body resp)))

(def finding-body
  {:id "f-1"
   :title "Signup form returns 500 on valid input"
   :severity :medium
   :target {:repo "net-kotobase/site" :rev "abc1234" :surface "/signup"}
   :evidence {:steps ["open /signup" "submit a valid address"]
              :expected "201 and a confirmation mail"
              :observed "500, no mail"}})

(defn- post [c path body]
  (http/handle c {:method :post :path path :body (pr-str body)}))

(defn- get* [c path & [qp]]
  (http/handle c {:method :get :path path :query-params (or qp {})}))

;; ---- identity comes from the context ----

(deftest a-write-without-a-principal-is-refused
  (let [c (fresh-ctx nil)
        r (post c "/findings" finding-body)]
    (is (= 401 (:status r)))
    (is (= :no-authenticated-principal (:kenbun.error/reason (body-of r))))
    ;; The SAME ctx's store -- asking a freshly built one whether it is empty
    ;; is a question with only one possible answer.
    (is (empty? (store/list-entities (:store c) :finding nil)))))

(deftest a-malformed-principal-is-not-good-enough
  (doseq [bad [{:id "x"} {:kind :human} {:id "x" :kind :robot} "hana" nil]]
    (is (= 401 (:status (post (fresh-ctx bad) "/findings" finding-body)))
        (str "accepted principal " (pr-str bad)))))

(deftest the-reporter-is-the-principal-not-the-body
  (let [c (fresh-ctx {:id "hana" :kind :human})
        r (post c "/findings" finding-body)]
    (is (= 201 (:status r)))
    (is (= "hana" (get-in (body-of r) [:kenbun.intake/finding
                                       :kenbun.finding/reporter
                                       :kenbun.reporter/id])))
    (is (= :human (get-in (body-of r) [:kenbun.intake/finding
                                       :kenbun.finding/reporter
                                       :kenbun.reporter/kind])))))

(deftest a-body-that-names-a-reporter-is-rejected-not-ignored
  (let [c (fresh-ctx {:id "hana" :kind :human})
        r (post c "/findings" (assoc finding-body :reporter {:id "someone-else" :kind :human}))]
    (is (= 400 (:status r)))
    (is (= :reporter-not-accepted-in-body (:kenbun.error/reason (body-of r)))
        "silently dropping it looks the same to the client as honouring it")
    (is (empty? (store/list-entities (:store c) :finding nil))
        "and nothing was filed")))

(deftest a-body-cannot-claim-its-own-corroboration
  (let [c (fresh-ctx {:id "hana" :kind :human})
        r (post c "/findings" (assoc finding-body :reproduced-by #{"a" "b" "c"}))]
    (is (= 400 (:status r)))
    (is (= :reproduced-by-not-accepted-in-body (:kenbun.error/reason (body-of r)))
        "witnesses are earned by a second submission, not asserted -- otherwise
         a filer inflates their own credit and skips the review lane")))

;; ---- the three admission outcomes stay three on the wire ----

(deftest rejected-and-undecidable-get-different-statuses
  (let [c (fresh-ctx)]
    (testing "admitted"
      (is (= 201 (:status (post c "/findings" finding-body)))))
    (testing "examined and refused -> 422"
      (let [r (post c "/findings" (-> finding-body (assoc :id "f-2") (dissoc :evidence)))]
        (is (= 422 (:status r)))
        (is (= :no-evidence (get-in (body-of r) [:kenbun.intake/decision
                                                 :kenbun.admission/reason])))))
    (testing "could not be judged -> 409, a different status"
      (let [r (post c "/findings" (assoc finding-body :id "f-3" :severity :catastrophic))]
        (is (= 409 (:status r)))
        (is (= :undecidable (:kenbun.intake/outcome (body-of r))))))
    (testing "the two are not the same status"
      (is (not= 422 409)))))

(deftest a-second-reporter-corroborates-rather-than-filing-again
  (let [s (store/mem-store)
        hana {:store s :principal {:id "hana" :kind :human}}
        agent {:store s :principal {:id "agent-7" :kind :agent :model "murakumo-main"}}]
    (is (= 201 (:status (post hana "/findings" finding-body))))
    (let [r (post agent "/findings" (assoc finding-body :id "f-2" :title "cannot sign up"))]
      (is (= 200 (:status r)))
      (is (= :corroborated (:kenbun.intake/outcome (body-of r))))
      (is (= #{"agent-7"} (get-in (body-of r) [:kenbun.intake/finding
                                               :kenbun.finding/reproduced-by]))))
    (is (= 1 (count (store/list-entities s :issue nil))))))

;; ---- review lane has an exit ----

(deftest approving-a-high-severity-finding-confirms-it
  (let [c (fresh-ctx)
        filed (body-of (post c "/findings" (assoc finding-body :severity :high)))
        pid (get-in filed [:kenbun.intake/proposal :kotoba.issue.proposal/id])]
    (is (= :needs-human-review (get-in filed [:kenbun.intake/triage :kenbun.triage/lane])))
    (let [r (post c (str "/proposals/" pid "/reviews") {:verdict :approve :note "reproduced"})]
      (is (= 200 (:status r)))
      (is (= [:merged] (mapv :status (:kenbun.intake/merged (body-of r))))))
    (is (true? (:kenbun.finding/confirmed? (store/get-entity (:store c) :finding "f-1")))
        "without this the severest findings are the only ones with no exit")
    (is (= :triaged (:kotoba.issue/state (store/get-entity (:store c) :issue "issue:f-1"))))))

(deftest rejecting-a-proposal-does-not-confirm-the-finding
  (let [c (fresh-ctx)
        filed (body-of (post c "/findings" (assoc finding-body :severity :high)))
        pid (get-in filed [:kenbun.intake/proposal :kotoba.issue.proposal/id])
        r (post c (str "/proposals/" pid "/reviews") {:verdict :reject :note "not a defect"})]
    (is (= 200 (:status r)))
    (is (nil? (:kenbun.intake/merged (body-of r))))
    (is (not (:kenbun.finding/confirmed? (store/get-entity (:store c) :finding "f-1"))))))

(deftest reviewing-a-terminal-proposal-is-a-conflict-with-its-reason-kept
  (let [c (fresh-ctx)
        filed (body-of (post c "/findings" (assoc finding-body :severity :high)))
        pid (get-in filed [:kenbun.intake/proposal :kotoba.issue.proposal/id])]
    (post c (str "/proposals/" pid "/reviews") {:verdict :approve})
    (let [r (post c (str "/proposals/" pid "/reviews") {:verdict :reject})]
      (is (= 409 (:status r)))
      (is (string? (get-in (body-of r) [:kenbun.error/detail :message]))
          "the underlying message is kept, not replaced with a generic string"))))

(deftest an-unknown-verdict-and-an-unknown-proposal-differ
  (let [c (fresh-ctx)]
    (is (= 400 (:status (post c "/proposals/p-x/reviews" {:verdict :lgtm}))))
    (is (= 404 (:status (post c "/proposals/p-x/reviews" {:verdict :approve}))))))

;; ---- reads ----

(deftest reads-need-no-principal
  (let [c {:store (store/mem-store) :principal nil}]
    (is (= 200 (:status (get* c "/findings"))))
    (is (= 200 (:status (get* c "/issues"))))
    (is (= 200 (:status (get* c "/report"))))))

(deftest listing-filters-by-lane-and-state
  (let [c (fresh-ctx)]
    (post c "/findings" (assoc finding-body :severity :high))
    (post c "/findings" (assoc finding-body :id "f-2" :severity :low
                               :evidence {:steps ["x"] :expected "a" :observed "b"}))
    (is (= 2 (count (:kenbun.list/findings (body-of (get* c "/findings"))))))
    (is (= 1 (count (:kenbun.list/findings
                     (body-of (get* c "/findings" {"lane" "needs-human-review"}))))))
    (is (= 2 (count (:kenbun.list/issues (body-of (get* c "/issues"))))))
    (is (= 1 (count (:kenbun.list/issues (body-of (get* c "/issues" {"lane" "needs-second-repro"}))))))))

(deftest a-missing-finding-is-404-not-an-empty-body
  (is (= 404 (:status (get* (fresh-ctx) "/findings/nope")))))

(deftest the-report-does-not-claim-a-submission-total
  (let [c (fresh-ctx)]
    (post c "/findings" finding-body)
    (post c "/findings" (-> finding-body (assoc :id "f-2") (dissoc :evidence)))
    (let [b (body-of (get* c "/report"))]
      (is (= 1 (:kenbun.report/findings-on-file b)))
      (is (not-any? #(re-find #"submitted" (name %)) (keys b))
          "refused submissions leave no entity, so counting what is on file as
           if it were everything submitted would repeat the collapse the
           admission gate refuses")
      (is (string? (:kenbun.report/note b))))))

;; ---- wire ----

(deftest malformed-edn-is-a-400-not-a-crash
  (is (= 400 (:status (http/handle (fresh-ctx)
                                   {:method :post :path "/findings" :body "{:unclosed "})))))

(deftest unknown-routes-are-404
  (is (= 404 (:status (get* (fresh-ctx) "/nope"))))
  (is (= 404 (:status (http/handle (fresh-ctx) {:method :delete :path "/findings/f-1"})))))

(deftest every-response-is-edn
  (let [c (fresh-ctx)]
    (doseq [r [(post c "/findings" finding-body)
               (get* c "/report")
               (get* c "/nope")]]
      (is (= http/edn-content-type (get-in r [:headers "content-type"])))
      (is (map? (edn/read-string (:body r)))))))
