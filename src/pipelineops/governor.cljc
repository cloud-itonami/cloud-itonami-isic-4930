(ns pipelineops.governor
  "PipelineTransportGovernor -- the independent compliance layer that
  earns the PipelineTransportAdvisor the right to commit. The advisor
  has no notion of whether a pipeline segment is actually registered and
  operator-license-verified, whether a named maintenance contractor is
  itself a registered/verified counterparty, whether its own proposed
  `:effect` secretly claims a direct actuation instead of a mere
  proposal, or whether it has silently drifted into a permanently
  out-of-scope decision area, so this MUST be a separate system able to
  *reject* a proposal and fall back to HOLD.

  THIS ACTOR'S SCOPE IS DELIBERATELY, PERMANENTLY NARROW --
  ADMINISTRATIVE / LOGISTICS COORDINATION ONLY (flow-volume/billing
  telemetry record logging, pipeline-integrity-inspection /
  right-of-way maintenance-crew scheduling, maintenance-crew/equipment
  procurement coordination, integrity-concern flagging). This is
  high-hazard critical infrastructure (oil/gas/chemical pipeline
  transport, catastrophic-failure-mode risk: explosion, toxic release,
  environmental contamination). It NEVER performs, authorizes or even
  PROPOSES anything that could be construed as controlling pipeline
  valves, pressure, flow rate, or shutoff systems -- there is no op in
  the closed allowlist below that resembles operational control of the
  pipeline itself, not merely a gate on one. It NEVER performs or
  authorizes:
    - adjusting or overriding a pipeline operating parameter (pressure,
      flow rate, valve state)
    - directly finalizing a pipeline-integrity-safety-clearance
    - overriding, bypassing or disabling an emergency shutoff system
    - any other form of operational control of the pipeline

  Four HARD checks, ALL permanent, un-overridable by any human approval:

    1. Segment unverified          -- the target pipeline-segment record
                                       must exist AND be independently
                                       confirmed
                                       `:registered?`/`:verified?` in the
                                       store before ANY proposal for it
                                       may commit or even escalate. Never
                                       trusts a proposal's own claim
                                       about the segment -- re-derived
                                       from the segment's own record, the
                                       same 'ground truth, not
                                       self-report' discipline every
                                       sibling actor's governor uses.
    2. Contractor unverified       -- for `:coordinate-maintenance-order`
                                       ONLY, the proposal's own drafted
                                       `:value` must name a
                                       `:contractor-id` that resolves to
                                       an independently
                                       `:registered?`/`:verified?`
                                       maintenance-contractor record. A
                                       missing contractor-id, or one that
                                       resolves to an unregistered or
                                       unverified contractor, is a HARD
                                       block -- the flagship genuinely
                                       new check this vertical adds (a
                                       maintenance-contractor
                                       counterparty-verification gate).
    3. Effect not :propose         -- every proposal's `:effect` MUST be
                                       `:propose`. Any other effect value
                                       is, by construction, a claim to
                                       directly actuate/commit outside
                                       governance -- HARD block, not
                                       merely low-confidence. There is no
                                       legitimate `:effect` other than
                                       `:propose` for this actor, ever --
                                       unlike some sibling actors, this
                                       actor structurally has NOTHING it
                                       could ever be authorized to
                                       actuate directly.
    4. Scope exclusion             -- ANY proposal (regardless of op)
                                       whose op, summary, rationale,
                                       cites or draft value touches
                                       directly finalizing a
                                       pipeline-integrity-safety-
                                       clearance, adjusting/overriding a
                                       pressure or flow-rate parameter,
                                       or overriding/bypassing/disabling
                                       an emergency shutoff, is a HARD,
                                       PERMANENT block -- this actor's
                                       charter excludes that territory
                                       structurally, not as a rollout
                                       milestone. Evaluated
                                       UNCONDITIONALLY on every proposal.
                                       An op outside the closed four-op
                                       allowlist is the SAME failure mode
                                       (an advisor proposing something it
                                       was never authorized to propose)
                                       and is folded into this same
                                       check. `:flag-integrity-concern`
                                       itself is never excluded by this
                                       check -- surfacing a
                                       leak-detection/pressure-anomaly/
                                       corrosion/integrity-inspection-
                                       failure concern for a human is
                                       exactly this actor's job; only
                                       FINALIZING/adjusting/overriding
                                       that concern's underlying
                                       operating parameter is excluded
                                       (see `scope-excluded-terms` below
                                       -- phrased as the
                                       finalization/execution ACTION,
                                       never a bare noun like 'pressure'
                                       or 'shutoff' or 'valve', so the
                                       default mock advisor's own
                                       `:flag-integrity-concern`
                                       rationale, which legitimately
                                       talks about pressure anomalies and
                                       leak detection as OBSERVATIONS,
                                       never self-trips this check).

  Two ESCALATE (SOFT) gates, either forces human sign-off:
    - LLM confidence below the floor.
    - The op is `:flag-integrity-concern` -- ALWAYS escalates to a
      human immediately, regardless of confidence, regardless of how
      clean the proposal otherwise is. Safety-critical, not merely a
      business-process convenience: a leak-detection/pressure-anomaly/
      corrosion/integrity-inspection-failure concern must always reach a
      human without delay. `pipelineops.phase` independently agrees:
      `:flag-integrity-concern` is never a member of any phase's `:auto`
      set either -- two layers, not one.
    - A `:coordinate-maintenance-order` whose drafted `:value` names an
      `:estimated-cost` above `maintenance-cost-threshold` -- a
      large-value maintenance-crew/equipment procurement proposal always
      needs a human sign-off, even when the governor and phase would
      otherwise allow auto-commit."
  (:require [clojure.string :as str]
            [pipelineops.store :as store]))

(def confidence-floor 0.6)

(def maintenance-cost-threshold
  "Example single-segment maintenance-crew/equipment procurement
  threshold (USD-equivalent units, domain-illustrative -- not a
  universal cross-domain constant). A `:coordinate-maintenance-order`
  proposal citing an `:estimated-cost` above this value ALWAYS escalates
  to human sign-off, regardless of confidence or rollout phase."
  5000.0)

(def allowed-ops
  "The closed proposal-op allowlist -- ADMINISTRATIVE/LOGISTICS
  COORDINATION ONLY. An op outside this set is a scope violation by
  construction (see `scope-exclusion-violations`). Deliberately contains
  NO op that resembles operational control of the pipeline (no
  pressure/flow-rate/valve/shutoff adjustment op exists here at all,
  even as a gated 'propose' op)."
  #{:log-throughput-record :schedule-inspection-operation
    :coordinate-maintenance-order :flag-integrity-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-integrity-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- directly finalizing a
  pipeline-integrity-safety-clearance, adjusting/overriding a pressure or
  flow-rate operating parameter, or overriding/bypassing/disabling an
  emergency shutoff system, i.e. anything that could be construed as
  operational control of the pipeline rather than administrative
  coordination about it. Scanned across the proposal's
  op/summary/rationale/cites/value, never trusting the advisor's own
  framing of its intent.

  CRITICAL: every term here is phrased as the finalization/execution
  ACTION (e.g. 'adjusted the pipeline pressure', 'overrode the emergency
  shutoff'), never a bare noun like 'pressure', 'flow rate', 'valve',
  'shutoff' or 'clearance' -- a bare noun would accidentally match inside
  this actor's own legitimate `:flag-integrity-concern` default proposal
  text (whose whole job is to talk about pressure anomalies, leak
  detection and corrosion as OBSERVATIONS) or the routine `:value`
  fields other ops legitimately carry, and self-block the happy path.
  See
  `pipelineops.governor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`
  for the regression test."
  ["finalize the integrity-safety clearance" "finalized the integrity-safety clearance"
   "finalizes the integrity-safety clearance" "finalizing the integrity-safety clearance"
   "issue the safety clearance certificate" "issued the safety clearance certificate"
   "issuing the safety clearance certificate"
   "grant the integrity-safety clearance" "granted the integrity-safety clearance"
   "adjust the pipeline pressure" "adjusted the pipeline pressure" "adjusting the pipeline pressure"
   "change the pressure setpoint" "changed the pressure setpoint" "changing the pressure setpoint"
   "increase the flow rate" "increased the flow rate" "increasing the flow rate"
   "decrease the flow rate" "decreased the flow rate" "decreasing the flow rate"
   "modify the flow rate" "modified the flow rate" "modifying the flow rate"
   "override the emergency shutoff" "overrode the emergency shutoff" "overriding the emergency shutoff"
   "bypass the emergency shutoff" "bypassed the emergency shutoff" "bypassing the emergency shutoff"
   "disable the emergency shutoff" "disabled the emergency shutoff" "disabling the emergency shutoff"
   "open the block valve" "opened the block valve" "opening the block valve"
   "close the block valve" "closed the block valve" "closing the block valve"
   "actuate the valve" "actuated the valve" "actuating the valve"
   "operate the shutoff valve" "operated the shutoff valve" "operating the shutoff valve"
   "圧力を調整した" "圧力を調整する" "圧力を調整して" "圧力設定値を変更した"
   "流量を変更した" "流量を増加させた" "流量を減少させた" "流量を調整した"
   "緊急遮断を解除した" "緊急遮断を無効化した" "緊急遮断を回避した"
   "遮断弁を操作した" "バルブを開放した" "バルブを閉止した" "バルブを作動させた"
   "安全クリアランスを確定した" "整合性クリアランスを発行した" "整合性クリアランスを確定した"])

;; ----------------------------- checks -----------------------------

(defn- segment-unverified-violations
  "The target pipeline segment must exist AND be independently
  `:registered?`/`:verified?` in the store -- never trust the proposal's
  own `:segment-id` claim without a store lookup."
  [{:keys [segment-id]} st]
  (let [s (store/segment-record st segment-id)]
    (when-not (and s (:registered? s) (:verified? s))
      [{:rule :segment-unverified
        :detail (str segment-id " は未登録または未検証のパイプライン区間 -- いかなる提案も進められない")}])))

(defn- contractor-unverified-violations
  "For `:coordinate-maintenance-order` ONLY, the proposal's own drafted
  `:value` must name a `:contractor-id` that resolves to an
  independently `:registered?`/`:verified?` contractor record. A missing
  contractor-id, or one that resolves to an unregistered/unverified
  contractor, is a HARD block -- never trust the proposal's own
  contractor claim without a store lookup, the SAME 'ground truth, not
  self-report' discipline as `segment-unverified-violations`, reapplied
  to the maintenance counterparty."
  [proposal st]
  (when (= :coordinate-maintenance-order (:op proposal))
    (let [contractor-id (get-in proposal [:value :contractor-id])
          c (and contractor-id (store/contractor-record st contractor-id))]
      (when-not (and c (:registered? c) (:verified? c))
        [{:rule :contractor-unverified
          :detail (str (or contractor-id "(contractor-id missing)")
                        " は未登録または未検証の保守請負業者 -- 発注調整提案を進められない")}]))))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim to
  directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one lower-cased
  blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist, or
  one whose content touches directly finalizing a
  pipeline-integrity-safety-clearance or adjusting/overriding a pressure/
  flow-rate/valve/shutoff operating parameter, regardless of confidence
  or how clean every other check is. Evaluated UNCONDITIONALLY on every
  proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "圧力/流量パラメータ調整・バルブ操作・緊急遮断の解除・完全性安全クリアランスの確定など、パイプラインの操作制御に触れる提案は永久に禁止"}])))

(defn- high-cost-maintenance-order?
  "A `:coordinate-maintenance-order` proposal citing an `:estimated-cost`
  above `maintenance-cost-threshold` -- always needs human sign-off
  (SOFT escalate, not a hard block: the order itself is in scope, only
  its size requires a human)."
  [proposal]
  (and (= :coordinate-maintenance-order (:op proposal))
       (some-> proposal :value :estimated-cost (> maintenance-cost-threshold))))

(defn check
  "Censors a PipelineTransportAdvisor proposal against the governor
  rules. Returns {:ok? bool :violations [..] :confidence c :escalate?
  bool :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [segment-id (or (:segment-id proposal) (:segment-id request))
        hard (into []
                   (concat (segment-unverified-violations {:segment-id segment-id} store)
                           (contractor-unverified-violations proposal store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (or (always-escalate-ops (:op proposal))
                              (high-cost-maintenance-order? proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :segment-id (:segment-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
