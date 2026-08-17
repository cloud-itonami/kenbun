(ns kenbun.store.kotobase-durable-test
  "The same adapter, against a provider that survives the process.

  `kotobase_test` runs everything against `storage.memory`, which is enough to
  show the adapter's logic is right and not enough to show it persists — an
  in-memory provider cannot fail to survive a restart, because there is no
  restart to survive. So this namespace opens a real SQLite file, closes the
  database, opens a SECOND database over the same file, and asks the second
  one what the first one wrote.

  That is the whole point: `README` claimed the adapter was provider-neutral
  and admitted the claim had only ever been measured against memory. This is
  the measurement."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kenbun.intake :as intake]
            [kenbun.store.kotobase :as kbstore]
            [kotobase.core :as kb]
            [kotobase.storage.sqlite :as sqlite]
            [kotoba.issue.gate :as gate]
            [kotoba.issue.store :as store]))

(defn- temp-db-path []
  (let [f (java.io.File/createTempFile "kenbun-durable" ".sqlite")]
    (.delete f)                      ; the provider creates it
    (.deleteOnExit f)
    (.getAbsolutePath f)))

(defn- open-store
  "A fresh kotobase database over `path`, wrapped as an IssueStore. Calling
  this twice on one path is what makes the durability question answerable."
  [path]
  (kbstore/kotobase-store
   (kb/open {:storage (sqlite/open {:path path})
             :encrypt-fn identity
             :decrypt-fn identity
             :blind-fn pr-str
             :visible? (constantly true)})))

(def ^:private submission
  {:id "f-1"
   :title "Signup form returns 500 on valid input"
   :severity :medium
   :reporter {:id "hana" :kind :human}
   :target {:repo "net-kotobase/site" :rev "abc1234" :surface "/signup"}
   :evidence {:steps ["open /signup" "submit a valid address"]
              :expected "201 and a confirmation mail"
              :observed "500, no mail"}})

(deftest what-one-database-wrote-a-later-one-reads
  (let [path (temp-db-path)]
    (testing "first database files a finding"
      (let [s (open-store path)
            r (intake/submit! s submission)]
        (is (= :filed (:kenbun.intake/outcome r)))))

    (testing "a SECOND database over the same file sees it"
      (let [s (open-store path)
            f (store/get-entity s :finding "f-1")]
        (is (some? f) "nothing survived the reopen")
        (is (= "Signup form returns 500 on valid input" (:kenbun.finding/title f)))
        (is (= :medium (:kenbun.finding/severity f)))
        (is (= "hana" (get-in f [:kenbun.finding/reporter :kenbun.reporter/id])))
        (is (= ["open /signup" "submit a valid address"]
               (get-in f [:kenbun.finding/evidence :kenbun.evidence/steps]))
            "nested structure survives the encode/decode round trip on disk")))

    (testing "and the issue, proposal and audit trail survive too"
      (let [s (open-store path)]
        (is (= 1 (count (store/list-entities s :issue nil))))
        (is (= 1 (count (store/list-entities s :proposal nil))))
        (is (= [:issue/opened :proposal/proposed]
               (mapv :kotoba.issue.audit/type (kbstore/audit-log s))))))))

(deftest dedupe-holds-across-a-reopen
  (testing "a corroborating report filed against a NEW database still finds
            the original defect — the property the whole service depends on,
            and the one an in-memory provider cannot test"
    (let [path (temp-db-path)]
      (intake/submit! (open-store path) submission)
      (let [s (open-store path)
            r (intake/submit! s (assoc submission
                                       :id "f-2"
                                       :title "cannot sign up at all"
                                       :reporter {:id "agent-7" :kind :agent}))]
        (is (= :corroborated (:kenbun.intake/outcome r)))
        (is (= #{"agent-7"} (get-in r [:kenbun.intake/finding
                                       :kenbun.finding/reproduced-by])))
        (is (= 1 (count (store/list-entities s :issue nil)))
            "a second issue here would mean dedupe silently stopped working
             once the candidates had to come off disk")))))

(deftest a-status-transition-survives-a-reopen-as-the-latest-value
  (testing "the accumulate bug, checked against a durable provider rather
            than only against memory"
    (let [path (temp-db-path)]
      (let [s (open-store path)]
        (store/put-entity! s :proposal "p-1" {:kotoba.issue.proposal/status :proposed})
        (store/put-entity! s :proposal "p-1" {:kotoba.issue.proposal/status :approved}))
      (let [s (open-store path)]
        (store/put-entity! s :proposal "p-1" {:kotoba.issue.proposal/status :merged})
        (is (= :merged (:kotoba.issue.proposal/status
                        (store/get-entity s :proposal "p-1")))))
      (testing "and once more from a third database"
        (is (= :merged (:kotoba.issue.proposal/status
                        (store/get-entity (open-store path) :proposal "p-1"))))))))

(deftest a-full-lifecycle-persists-to-disk
  (let [path (temp-db-path)
        filed (intake/submit! (open-store path) (assoc submission :severity :high))
        pid (get-in filed [:kenbun.intake/proposal :kotoba.issue.proposal/id])]
    (let [s (open-store path)]
      (gate/approve! s pid {:decider "hana" :note "reproduced"})
      (gate/merge! s (intake/merge-handlers s) {:proposal-id pid}))
    (let [s (open-store path)]
      (is (= :merged (:kotoba.issue.proposal/status (store/get-entity s :proposal pid))))
      (is (true? (:kenbun.finding/confirmed? (store/get-entity s :finding "f-1"))))
      (is (= :triaged (:kotoba.issue/state (store/get-entity s :issue "issue:f-1"))))
      (is (= [:issue/opened :proposal/proposed :review/approved :merge/merged]
             (mapv :kotoba.issue.audit/type (kbstore/audit-log s)))
          "audit order is recovered from the stored sequence, on disk"))))

(deftest the-file-is-actually-on-disk
  (testing "if the provider silently fell back to memory, every test above
            would pass while persisting nothing"
    (let [path (temp-db-path)]
      (intake/submit! (open-store path) submission)
      (let [f (io/file path)]
        (is (.exists f) "no database file was created")
        (is (pos? (.length f)) "the database file is empty")))))
