(ns kenbun.intake
  "The pipeline, bound onto `kotoba.issue.gate`.

  Everything else in this repository is a pure decision. This is the only
  namespace that writes, and it writes through the `kotoba.issue.store`
  contract — so a deployment supplies kotobase, Datomic, or the in-memory
  store without this file changing.

  The division of labour is the point:

    kenbun.*            decides whether a claim is a defect report
    kotoba.issue.gate   owns the issue/proposal/review/merge state machine
                        and appends the audit record for every transition

  `submit!` never throws for a bad submission. A malformed finding is a
  result, not an exception — an intake that crashes on the input it exists to
  screen loses the input."
  (:require [kenbun.credit :as credit]
            [kenbun.dedupe :as dedupe]
            [kenbun.evidence :as evidence]
            [kenbun.finding :as finding]
            [kenbun.triage :as triage]
            [kotoba.issue.gate :as gate]
            [kotoba.issue.store :as store]))

(def outcomes
  "Every way `submit!` can end. `:rejected` and `:undecidable` are distinct
  outcomes all the way to the caller — see `kenbun.evidence`."
  #{:filed :corroborated :rejected :undecidable})

(defn- result [outcome m]
  (assoc m :kenbun.intake/outcome outcome))

(defn- candidates
  "Findings already on file that could be the same defect. Narrowed by
  fingerprint so a store can index on it; `dedupe/duplicate-of` then compares
  exact keys, so a fingerprint collision costs a comparison, not a merge."
  [s f]
  (let [fp (dedupe/fingerprint f)]
    (store/list-entities s :finding #(= fp (:kenbun.finding/fingerprint %)))))

(defn submit!
  "Screen a submitted finding and, if it holds up, file or corroborate it.

  Returns a map whose `:kenbun.intake/outcome` is one of `outcomes`. On
  `:filed` it also carries the opened issue, the proposal raised against it,
  the triage record, and the credit split."
  [s submission]
  (let [f0 (finding/finding submission)
        decision (evidence/admit f0)]
    (case (:kenbun.admission/verdict decision)
      :undecidable
      (result :undecidable {:kenbun.intake/decision decision
                            :kenbun.intake/finding-id (:kenbun.finding/id f0)})

      :rejected
      (result :rejected {:kenbun.intake/decision decision
                         :kenbun.intake/finding-id (:kenbun.finding/id f0)})

      :admitted
      (let [f (assoc f0 :kenbun.finding/fingerprint (dedupe/fingerprint f0))]
        (if-let [original (dedupe/duplicate-of f (candidates s f))]
          ;; Same defect, second reporter: one issue, two witnesses.
          (let [merged (dedupe/merge-witness original f finding/severity-rank)
                id (:kenbun.finding/id merged)]
            (store/put-entity! s :finding id merged)
            (store/append-audit! s (gate/audit {:id (str "kenbun:corroborated:" id ":"
                                                         (get-in f [:kenbun.finding/reporter
                                                                    :kenbun.reporter/id]))
                                                :type :kenbun/corroborated
                                                :issue (:kenbun.finding/issue merged)
                                                :source-event (pr-str
                                                               {:by (get-in f [:kenbun.finding/reporter
                                                                               :kenbun.reporter/id])
                                                                :duplicate-of id})}))
            (result :corroborated {:kenbun.intake/finding merged
                                   :kenbun.intake/finding-id id
                                   :kenbun.intake/decision decision
                                   :kenbun.intake/triage (triage/triage merged)
                                   :kenbun.intake/credit (credit/split merged)}))

          ;; New defect: open the issue, raise the proposal against it.
          (let [t (triage/triage f)
                fid (:kenbun.finding/id f)
                issue-id (str "issue:" fid)
                issue (gate/open-issue! s {:id issue-id
                                           :kind :kenbun/defect
                                           :title (:kenbun.finding/title f)
                                           :source (if (finding/agent-reported? f)
                                                     :kenbun/agent
                                                     :kenbun/human)
                                           :source-id (get-in f [:kenbun.finding/reporter
                                                                 :kenbun.reporter/id])
                                           :lane (:kenbun.triage/lane t)
                                           :repo (get-in f [:kenbun.finding/target
                                                            :kenbun.target/repo])})
                proposal (gate/propose! s {:id (str "proposal:" fid)
                                           :issue issue-id
                                           :kind :kenbun/file-defect
                                           :risk (:kenbun.triage/risk t)
                                           :rationale (:kenbun.finding/title f)
                                           :payload {:finding-id fid
                                                     :fingerprint (:kenbun.finding/fingerprint f)
                                                     :severity (:kenbun.finding/severity f)}})]
            (store/put-entity! s :finding fid (assoc f :kenbun.finding/issue issue-id))
            (result :filed {:kenbun.intake/finding (store/get-entity s :finding fid)
                            :kenbun.intake/finding-id fid
                            :kenbun.intake/decision decision
                            :kenbun.intake/issue issue
                            :kenbun.intake/proposal proposal
                            :kenbun.intake/triage t
                            :kenbun.intake/route (gate/route proposal)
                            :kenbun.intake/credit (credit/split f)})))))))

(defn intake-report
  "Counts for a batch of submissions, with the same three-way split
  `kenbun.evidence/admit-batch` keeps. `:submitted` is an evidence floor: a
  report whose counts sum to less than it has lost submissions somewhere, and
  a report of zero submitted is not a report of zero problems."
  [results]
  (let [by (frequencies (map :kenbun.intake/outcome results))]
    {:kenbun.intake/submitted (count results)
     :kenbun.intake/filed (get by :filed 0)
     :kenbun.intake/corroborated (get by :corroborated 0)
     :kenbun.intake/rejected (get by :rejected 0)
     :kenbun.intake/undecidable (get by :undecidable 0)}))
