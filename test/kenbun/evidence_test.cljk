(ns kenbun.evidence-test
  "Every check is shown in BOTH directions: the input it rejects and the
  input it admits. A check that has only ever been seen refusing is not known
  to accept anything, and one that has only ever been seen accepting is not
  known to refuse — either way nobody can act on its output."
  (:require [clojure.test :refer [deftest is testing]]
            [kenbun.evidence :as evidence]
            [kenbun.finding :as finding]))

(defn- base
  "A finding that is admitted, so each test can break exactly one thing."
  [overrides]
  (finding/finding
   (merge {:id "f-1"
           :title "Signup form returns 500 on valid input"
           :severity :medium
           :reporter {:id "r-1" :kind :human}
           :target {:repo "net-kotobase/site" :rev "abc1234" :surface "/signup"}
           :evidence {:steps ["open /signup" "submit a valid address"]
                      :expected "201 and a confirmation mail"
                      :observed "500, no mail"}}
          overrides)))

(deftest baseline-is-admitted
  (is (= :admitted (:kenbun.admission/verdict (evidence/admit (base {}))))
      "the unbroken fixture must pass, or every rejection below proves nothing"))

(deftest missing-evidence-is-rejected-not-admitted
  (testing "no evidence bundle at all"
    (is (= :rejected (:kenbun.admission/verdict (evidence/admit (base {:evidence nil}))))))
  (testing "the reason survives"
    (is (= :no-evidence (:kenbun.admission/reason (evidence/admit (base {:evidence nil})))))))

(deftest no-repro-steps-is-rejected
  (let [d (evidence/admit (base {:evidence {:steps []
                                            :expected "201"
                                            :observed "500"}}))]
    (is (= :rejected (:kenbun.admission/verdict d)))
    (is (= :no-repro-steps (:kenbun.admission/reason d)))))

(deftest one-sided-claim-does-not-discriminate
  (testing "observed with no expected"
    (let [d (evidence/admit (base {:evidence {:steps ["open /signup"] :observed "500"}}))]
      (is (= :rejected (:kenbun.admission/verdict d)))
      (is (= :no-discrimination (:kenbun.admission/reason d)))
      (is (false? (get-in d [:kenbun.admission/detail :has-expected])))
      (is (true? (get-in d [:kenbun.admission/detail :has-observed])))))
  (testing "expected with no observed"
    (is (= :no-discrimination
           (:kenbun.admission/reason
            (evidence/admit (base {:evidence {:steps ["open /signup"] :expected "201"}})))))))

(deftest expected-equal-to-observed-is-not-a-defect
  (testing "identical modulo case and whitespace"
    (is (= :expected-equals-observed
           (:kenbun.admission/reason
            (evidence/admit (base {:evidence {:steps ["open /signup"]
                                              :expected "returns 500"
                                              :observed "Returns   500"}}))))))
  (testing "digits are NOT normalized away — these are different defects"
    (is (= :admitted
           (:kenbun.admission/verdict
            (evidence/admit (base {:evidence {:steps ["open /signup"]
                                              :expected "returns 404"
                                              :observed "returns 500"}})))))))

(deftest hedged-evidence-contradicts-a-high-severity-claim
  (let [hedged {:steps ["open /signup"]
                :expected "201"
                :observed "500, though I could not reproduce it a second time"}]
    (testing "rejected at :critical"
      (is (= :evidence-contradicts-severity
             (:kenbun.admission/reason
              (evidence/admit (base {:severity :critical :evidence hedged}))))))
    (testing "rejected at :high"
      (is (= :evidence-contradicts-severity
             (:kenbun.admission/reason
              (evidence/admit (base {:severity :high :evidence hedged}))))))
    (testing "ADMITTED at :low — a hedge is an honest confidence statement
              below the floor, and rejecting it would punish accurate
              reporting of a genuinely intermittent defect"
      (is (= :admitted
             (:kenbun.admission/verdict
              (evidence/admit (base {:severity :low :evidence hedged}))))))))

(deftest the-bar-does-not-depend-on-who-is-reporting
  (let [thin {:steps [] :expected "201" :observed "500"}
        good {:steps ["open /signup"] :expected "201" :observed "500"}]
    (doseq [kind [:human :agent]]
      (testing (str "thin evidence rejected for " kind)
        (is (= :rejected (:kenbun.admission/verdict
                          (evidence/admit (base {:reporter {:id "r" :kind kind}
                                                 :evidence thin}))))))
      (testing (str "good evidence admitted for " kind)
        (is (= :admitted (:kenbun.admission/verdict
                          (evidence/admit (base {:reporter {:id "r" :kind kind}
                                                 :evidence good})))))))))

;; ---- the class this repository exists to prevent ----

(deftest could-not-decide-is-not-a-pass-and-not-a-rejection
  (testing "unknown severity"
    (let [d (evidence/admit (base {:severity :catastrophic}))]
      (is (= :undecidable (:kenbun.admission/verdict d)))
      (is (not= :admitted (:kenbun.admission/verdict d)))
      (is (not= :rejected (:kenbun.admission/verdict d))
          "silently rejecting what could not be evaluated hides the gap just
           as well as silently admitting it")))
  (testing "unknown reporter kind"
    (is (= :unknown-reporter-kind
           (:kenbun.admission/reason (evidence/admit (base {:reporter {:id "r" :kind :robot}}))))))
  (testing "missing title"
    (is (= :undecidable (:kenbun.admission/verdict (evidence/admit (base {:title "   "})))))))

(deftest batch-cannot-report-a-single-boolean
  (let [b (evidence/admit-batch [(base {:id "a"})
                                 (base {:id "b" :evidence nil})
                                 (base {:id "c" :severity :catastrophic})])]
    (is (= 3 (:kenbun.admission/scanned b)) "evidence floor: nothing was dropped")
    (is (= 1 (:kenbun.admission/admitted b)))
    (is (= 1 (:kenbun.admission/rejected b)))
    (is (= 1 (:kenbun.admission/undecidable b)))
    (is (not (contains? b :clean?))
        "no key may let a caller collapse undecidable into pass or fail"))
  (testing "an empty batch is not a clean batch"
    (let [b (evidence/admit-batch [])]
      (is (= 0 (:kenbun.admission/scanned b))
          "scanned=0 is reported, so a caller can refuse to call it clean"))))
