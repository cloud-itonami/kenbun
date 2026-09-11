(ns kenbun.intake-test
  (:require [clojure.test :refer [deftest is testing]]
            [kenbun.credit :as credit]
            [kenbun.dedupe :as dedupe]
            [kenbun.finding :as finding]
            [kenbun.intake :as intake]
            [kenbun.triage :as triage]
            [kotoba.issue.store :as store]))

(defn- sub
  [overrides]
  (merge {:id "f-1"
          :title "Signup form returns 500 on valid input"
          :severity :medium
          :reporter {:id "hana" :kind :human}
          :target {:repo "net-kotobase/site" :rev "abc1234" :surface "/signup"}
          :evidence {:steps ["open /signup" "submit a valid address"]
                     :expected "201 and a confirmation mail"
                     :observed "500, no mail"}}
         overrides))

(deftest a-good-finding-becomes-an-issue-with-an-audit-trail
  (let [s (store/mem-store)
        r (intake/submit! s (sub {}))]
    (is (= :filed (:kenbun.intake/outcome r)))
    (is (= "issue:f-1" (get-in r [:kenbun.intake/issue :kotoba.issue/id])))
    (is (= :open (get-in r [:kenbun.intake/issue :kotoba.issue/state])))
    (is (= :proposed (get-in r [:kenbun.intake/proposal :kotoba.issue.proposal/status])))
    (testing "kotoba.issue.gate recorded both transitions"
      (is (= [:issue/opened :proposal/proposed]
             (mapv :kotoba.issue.audit/type (store/audit-log s)))))))

(deftest a-rejected-finding-writes-no-issue
  (let [s (store/mem-store)
        r (intake/submit! s (sub {:evidence nil}))]
    (is (= :rejected (:kenbun.intake/outcome r)))
    (is (= :no-evidence (get-in r [:kenbun.intake/decision :kenbun.admission/reason])))
    (is (empty? (store/list-entities s :issue nil)) "no issue was opened")
    (is (empty? (store/audit-log s)) "and nothing was appended to the audit log")))

(deftest an-undecidable-finding-is-not-silently-rejected
  (let [s (store/mem-store)
        r (intake/submit! s (sub {:severity :catastrophic}))]
    (is (= :undecidable (:kenbun.intake/outcome r)))
    (is (empty? (store/list-entities s :issue nil)))
    (is (not= :rejected (:kenbun.intake/outcome r))
        "the caller must be able to tell 'could not judge' from 'judged and refused'")))

(deftest submit-does-not-throw-on-a-malformed-submission
  (let [s (store/mem-store)]
    (doseq [bad [{} {:id "x"} (sub {:reporter nil}) (sub {:target nil})]]
      (let [r (intake/submit! s bad)]
        (is (contains? intake/outcomes (:kenbun.intake/outcome r))
            "a malformed submission is a result, never an exception")))))

(deftest the-same-defect-in-different-words-is-one-issue-with-two-witnesses
  (let [s (store/mem-store)
        first-report (intake/submit! s (sub {}))
        ;; A different reporter, a different title, a different summary — the
        ;; same target and the same expected/observed pair.
        second-report (intake/submit! s (sub {:id "f-2"
                                              :title "cannot sign up at all"
                                              :reporter {:id "agent-7" :kind :agent
                                                         :model "murakumo-main"}}))]
    (is (= :filed (:kenbun.intake/outcome first-report)))
    (is (= :corroborated (:kenbun.intake/outcome second-report)))
    (is (= 1 (count (store/list-entities s :issue nil))) "still exactly one issue")
    (is (= #{"agent-7"} (get-in second-report [:kenbun.intake/finding
                                               :kenbun.finding/reproduced-by])))
    (testing "an agent corroborating a human counts as a witness"
      (is (= 1 (get-in second-report [:kenbun.intake/triage :kenbun.triage/witnesses]))))))

(deftest a-different-defect-on-the-same-surface-is-a-separate-issue
  (let [s (store/mem-store)]
    (intake/submit! s (sub {}))
    (let [r (intake/submit! s (sub {:id "f-3"
                                    :evidence {:steps ["open /signup"]
                                               :expected "201 and a confirmation mail"
                                               :observed "404, no route"}}))]
      (is (= :filed (:kenbun.intake/outcome r)))
      (is (= 2 (count (store/list-entities s :issue nil)))))))

(deftest an-unevidenced-me-too-cannot-inflate-severity
  (let [original (finding/finding (sub {:severity :low}))
        loud (finding/finding (sub {:id "f-9" :severity :critical
                                    :reporter {:id "farmer" :kind :human}
                                    :evidence nil}))
        merged (dedupe/merge-witness original loud finding/severity-rank)]
    (is (= :low (:kenbun.finding/severity merged))
        "restating someone else's finding louder must not raise it")
    (is (contains? (:kenbun.finding/reproduced-by merged) "farmer")
        "but the reporter is still recorded as having shown up")))

(deftest intake-report-keeps-the-three-way-split
  (let [s (store/mem-store)
        rs [(intake/submit! s (sub {}))
            (intake/submit! s (sub {:id "f-b" :evidence nil}))
            (intake/submit! s (sub {:id "f-c" :severity :catastrophic}))]
        rep (intake/intake-report rs)]
    (is (= 3 (:kenbun.intake/submitted rep)))
    (is (= 1 (:kenbun.intake/filed rep)))
    (is (= 1 (:kenbun.intake/rejected rep)))
    (is (= 1 (:kenbun.intake/undecidable rep)))))

;; ---- triage / risk / credit ----

(deftest a-high-severity-claim-always-reaches-a-human
  (let [f (finding/finding (sub {:severity :critical :reproduced-by #{"a" "b" "c"}}))]
    (is (= :needs-human-review (triage/lane f))
        "agreement among reporters is not a substitute for a human deciding
         to publish 'this system has a critical defect'")
    (is (= :external-send (triage/risk f)))))

(deftest a-corroborated-low-severity-finding-files-itself
  (let [f (finding/finding (sub {:severity :low :reproduced-by #{"agent-7"}}))]
    (is (= :auto-file (triage/lane f)))
    (is (= :read-only (triage/risk f))))
  (testing "uncorroborated, it waits for a second reproduction"
    (is (= :needs-second-repro (triage/lane (finding/finding (sub {:severity :low})))))))

(deftest credit-is-deterministic-and-does-not-divide-the-filers-share
  (let [alone (finding/finding (sub {:severity :medium}))
        witnessed (finding/finding (sub {:severity :medium :reproduced-by #{"agent-7"}}))]
    (is (= 10 (credit/credit alone "hana")))
    (is (= 12 (credit/credit witnessed "hana")) "the filer gains from corroboration")
    (is (= 2 (credit/credit witnessed "agent-7")))
    (is (= 0 (credit/credit witnessed "stranger")))
    (is (= {"hana" 12 "agent-7" 2} (credit/split witnessed))))
  (testing "the bonus is paid once, however many witnesses arrive"
    (let [many (finding/finding (sub {:severity :medium
                                      :reproduced-by #{"a" "b" "c" "d" "e"}}))]
      (is (= 12 (credit/credit many "hana"))))))
