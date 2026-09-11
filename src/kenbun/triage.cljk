(ns kenbun.triage
  "Where an admitted finding goes, and what risk tier it carries into
  `kotoba.issue.gate`.

  Two axes, and only two:

    severity        — what the reporter claims is at stake
    witnesses       — how many OTHER reporters independently reproduced it

  Witnesses are the axis that makes a mixed human/agent service work. One
  reporter's confidence is not evidence of anything; a second reporter
  arriving at the same expected/observed pair is. Nothing here reads the
  reporter's kind: an agent corroborating a human counts exactly as much as a
  human corroborating an agent, because the corroboration is the
  reproduction, not the reporter."
  (:require [kenbun.finding :as finding]))

(def lanes
  "Where a finding lands.

    :auto-file        opened as an issue with no human in the loop
    :needs-second-repro   real enough to keep, not yet corroborated
    :needs-human-review   a human must look before it is opened"
  #{:auto-file :needs-second-repro :needs-human-review})

(def ^:private high (finding/severity-rank :high))

(defn lane
  "Route an admitted finding.

  A high/critical claim always goes to a human, however many witnesses it
  has — publishing 'this system has a critical defect' is an outward-facing
  act, and the number of agents that agree is not a substitute for someone
  deciding to say it."
  [f]
  (let [rank (finding/severity-rank (:kenbun.finding/severity f))
        witnesses (finding/independent-witnesses f)]
    (cond
      (nil? rank) :needs-human-review
      (>= rank high) :needs-human-review
      (pos? witnesses) :auto-file
      :else :needs-second-repro)))

(defn risk
  "The `kotoba.issue.gate` risk tier for the proposal this finding becomes.

  Filing an issue is a read-only act *inside* the workspace. It stops being
  read-only when the finding names a security defect, because opening that
  issue publishes an unpatched weakness — that is an external send, and
  `kotoba.issue.gate/route` will hold it for review accordingly."
  [f]
  (let [rank (finding/severity-rank (:kenbun.finding/severity f))]
    (if (or (:kenbun.finding/security? f) (and rank (>= rank high)))
      :external-send
      :read-only)))

(defn triage
  "Lane + risk + the witness count they were derived from. The count is
  returned rather than recomputed by callers so that a stored triage record
  can be checked against the finding it claims to describe."
  [f]
  {:kenbun.triage/lane (lane f)
   :kenbun.triage/risk (risk f)
   :kenbun.triage/witnesses (finding/independent-witnesses f)})
