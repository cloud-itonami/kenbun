(ns kenbun.store.kotobase-test
  "The adapter is only worth having if it is a true substitute for
  `mem-store`. So the same intake scenarios run against both, and the three
  measured hazards of the datom plane each get a test that fails without the
  corresponding defence."
  (:require [clojure.test :refer [deftest is testing]]
            [kenbun.intake :as intake]
            [kenbun.store.kotobase :as kbstore]
            [kotobase.core :as kb]
            [kotobase.storage.memory :as memory]
            [kotoba.issue.gate :as gate]
            [kotoba.issue.store :as store]))

(defn- fresh-store []
  (kbstore/kotobase-store
   (kb/open {:storage (memory/memory-store)
             :encrypt-fn identity
             :decrypt-fn identity
             :blind-fn pr-str
             :visible? (constantly true)})))

(defn- sub [overrides]
  (merge {:id "f-1"
          :title "Signup form returns 500 on valid input"
          :severity :medium
          :reporter {:id "hana" :kind :human}
          :target {:repo "net-kotobase/site" :rev "abc1234" :surface "/signup"}
          :evidence {:steps ["open /signup" "submit a valid address"]
                     :expected "201 and a confirmation mail"
                     :observed "500, no mail"}}
         overrides))

;; ---- hazard 1: transact! accumulates, it does not replace ----

(deftest a-rewritten-attribute-reads-back-as-the-latest-value
  (let [s (fresh-store)]
    (store/put-entity! s :proposal "p-1" {:kotoba.issue.proposal/status :proposed})
    (is (= :proposed (:kotoba.issue.proposal/status (store/get-entity s :proposal "p-1"))))
    (store/put-entity! s :proposal "p-1" {:kotoba.issue.proposal/status :approved})
    (store/put-entity! s :proposal "p-1" {:kotoba.issue.proposal/status :merged})
    (is (= :merged (:kotoba.issue.proposal/status (store/get-entity s :proposal "p-1")))
        "without retract-then-assert the plane keeps all three and returns an
         arbitrary one — a merged proposal reading back as still proposed")))

(deftest put-entity-merges-rather-than-replacing-the-whole-entity
  (let [s (fresh-store)]
    (store/put-entity! s :issue "i-1" {:kotoba.issue/title "t" :kotoba.issue/state :open})
    (store/put-entity! s :issue "i-1" {:kotoba.issue/state :closed})
    (let [e (store/get-entity s :issue "i-1")]
      (is (= :closed (:kotoba.issue/state e)))
      (is (= "t" (:kotoba.issue/title e)) "untouched attributes survive a partial write"))))

;; ---- hazard 2: Date is stringified with str, not pr-str ----

(deftest audit-timestamps-survive-the-round-trip
  (let [s (fresh-store)
        before (java.util.Date.)]
    (gate/open-issue! s {:id "i-1" :kind :kenbun/defect :title "t" :source :kenbun/human})
    (let [record (first (kbstore/audit-log s))
          at (:kotoba.issue.audit/at record)]
      (is (instance? java.util.Date at)
          "a raw Date comes back as \"Thu Jan 01 09:00:00 JST 1970\" — a string
           no reader accepts — unless values are pr-str encoded")
      (is (>= (.getTime ^java.util.Date at) (- (.getTime before) 1000))))))

;; ---- hazard 3: nil and "" both stringify to "" ----

(deftest nil-and-empty-string-stay-distinguishable
  (let [s (fresh-store)]
    (store/put-entity! s :issue "i-1" {:kotoba.issue/title "" :kotoba.issue/lane nil})
    (let [e (store/get-entity s :issue "i-1")]
      (is (= "" (:kotoba.issue/title e)))
      (is (nil? (:kotoba.issue/lane e)))
      (is (contains? e :kotoba.issue/lane) "an explicit nil is stored, not dropped"))))

;; ---- structural values ----

(deftest sets-and-nested-maps-round-trip
  (let [s (fresh-store)]
    (store/put-entity! s :finding "f-1"
                       {:kenbun.finding/reproduced-by #{"a" "b"}
                        :kenbun.finding/evidence {:kenbun.evidence/steps ["one" "two"]
                                                  :kenbun.evidence/expected "201"}})
    (let [e (store/get-entity s :finding "f-1")]
      (is (= #{"a" "b"} (:kenbun.finding/reproduced-by e)))
      (is (= ["one" "two"] (get-in e [:kenbun.finding/evidence :kenbun.evidence/steps]))))))

(deftest a-missing-entity-is-nil-not-an-empty-map
  (is (nil? (store/get-entity (fresh-store) :issue "nope"))
      "an empty map would read as an entity that exists with no attributes"))

(deftest list-entities-is-partitioned-by-kind
  (let [s (fresh-store)]
    (store/put-entity! s :issue "i-1" {:kotoba.issue/state :open})
    (store/put-entity! s :issue "i-2" {:kotoba.issue/state :closed})
    (store/put-entity! s :proposal "p-1" {:kotoba.issue.proposal/status :proposed})
    (is (= 2 (count (store/list-entities s :issue nil))))
    (is (= 1 (count (store/list-entities s :proposal nil))))
    (is (= 1 (count (store/list-entities s :issue #(= :open (:kotoba.issue/state %))))))
    (is (empty? (store/list-entities s :review nil)))))

(deftest the-audit-log-never-overwrites-a-record
  (testing "two records sharing one audit id both survive"
    (let [s (fresh-store)]
      (store/append-audit! s (gate/audit {:id "same" :type :issue/opened :issue "i-1"}))
      (store/append-audit! s (gate/audit {:id "same" :type :issue/closed :issue "i-1"}))
      (is (= 2 (count (kbstore/audit-log s)))
          "keying an append-only log on a non-unique id loses the earlier record")
      (is (= [:issue/opened :issue/closed]
             (mapv :kotoba.issue.audit/type (kbstore/audit-log s)))
          "and order is recovered from the stored sequence, not from set iteration"))))

;; ---- the real test: same behaviour as mem-store ----

(deftest intake-behaves-identically-on-both-stores
  (doseq [[label make] [["mem-store" #(store/mem-store)]
                        ["kotobase" fresh-store]]]
    (testing label
      (let [s (make)
            filed (intake/submit! s (sub {}))
            dup (intake/submit! s (sub {:id "f-2"
                                        :title "cannot sign up at all"
                                        :reporter {:id "agent-7" :kind :agent}}))
            rejected (intake/submit! s (sub {:id "f-3" :evidence nil}))
            undecidable (intake/submit! s (sub {:id "f-4" :severity :catastrophic}))]
        (is (= :filed (:kenbun.intake/outcome filed)))
        (is (= :corroborated (:kenbun.intake/outcome dup)))
        (is (= :rejected (:kenbun.intake/outcome rejected)))
        (is (= :undecidable (:kenbun.intake/outcome undecidable)))
        (is (= 1 (count (store/list-entities s :issue nil)))
            "one defect, filed once and corroborated once")
        (is (= #{"agent-7"} (get-in dup [:kenbun.intake/finding
                                         :kenbun.finding/reproduced-by])))
        (is (= {:kenbun.intake/submitted 4 :kenbun.intake/filed 1
                :kenbun.intake/corroborated 1 :kenbun.intake/rejected 1
                :kenbun.intake/undecidable 1}
               (intake/intake-report [filed dup rejected undecidable])))))))

(deftest a-full-issue-lifecycle-persists-through-the-datom-plane
  (let [s (fresh-store)
        filed (intake/submit! s (sub {:severity :high}))
        pid (get-in filed [:kenbun.intake/proposal :kotoba.issue.proposal/id])]
    (is (= :needs-human-review (get-in filed [:kenbun.intake/triage :kenbun.triage/lane])))
    (is (= :awaiting-review (:kenbun.intake/route filed))
        "a high-severity finding is :external-send, so gate/route holds it")
    (gate/approve! s pid {:decider "hana" :note "reproduced locally"})
    (is (= :approved (:kotoba.issue.proposal/status (store/get-entity s :proposal pid))))
    (let [merged (gate/merge! s {:kenbun/file-defect (constantly :ok)} {:proposal-id pid})]
      (is (= [:merged] (mapv :status merged)))
      (is (= :merged (:kotoba.issue.proposal/status (store/get-entity s :proposal pid)))
          "the terminal status is what reads back, not one of the earlier three"))
    (is (= [:issue/opened :proposal/proposed :review/approved :merge/merged]
           (mapv :kotoba.issue.audit/type (kbstore/audit-log s))))))
