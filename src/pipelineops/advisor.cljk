(ns pipelineops.advisor
  "PipelineTransportAdvisor -- the *contained intelligence node* for the
  ISIC-4930 'Transport via pipeline' operations-coordination actor.

  It drafts exactly four kinds of back-office ADMINISTRATIVE/LOGISTICS
  proposal from a closed allowlist: flow-volume/billing telemetry record
  logging, pipeline-integrity-inspection / right-of-way maintenance-crew
  scheduling, maintenance-crew/equipment procurement coordination with a
  registered contractor, and integrity-concern flagging (leak detection,
  pressure anomaly, corrosion, inspection failure). CRITICAL: it is a
  smart-but-untrusted advisor. It returns a *proposal* (with a rationale
  + the fields it cited), never a committed record and NEVER a direct
  actuation -- every proposal's `:effect` is always `:propose`. Every
  output is censored downstream by `pipelineops.governor` before
  anything touches the SSoT.

  This advisor NEVER drafts anything that adjusts or overrides a pipeline
  operating parameter (pressure, flow rate, valve state, emergency
  shutoff), never finalizes a pipeline-integrity-safety-clearance, and
  never proposes any form of operational control of the pipeline itself
  -- those are permanently out of scope for this actor, not merely
  un-implemented; the closed op allowlist below contains no such op at
  all. `pipelineops.governor`'s `scope-exclusion-violations`
  independently re-scans every proposal for exactly this failure mode (a
  compromised or confused advisor drifting into scope it must never
  touch) and HARD-holds it, regardless of confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so the
  actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op         kw             ; echoes the request op
     :segment-id str
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a direct actuation
     :value      map            ; the draft payload a human/system would review
     :confidence 0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-throughput-record
  "Draft a flow-volume/billing telemetry record log entry. Pure logging
  of observed metered throughput/custody-transfer volumes for billing and
  regulatory reporting -- never a control action, and never a claim about
  the current live operating state of the line."
  [_db {:keys [segment-id patch]}]
  {:op         :log-throughput-record
   :segment-id segment-id
   :summary    (str segment-id " の流量/計量請求記録を記録: " (pr-str (keys patch)))
   :rationale  "計量済み流量・カスタディトランスファー量の観察記録のみ。操作パラメータの判断は含まない。"
   :cites      [segment-id]
   :effect     :propose
   :value      (merge {:segment-id segment-id} patch)
   :confidence 0.93})

(defn- propose-inspection-operation
  "Draft a pipeline-integrity-inspection / right-of-way maintenance-crew
  scheduling proposal (a roster/calendar entry, never a direct
  operational action on the line)."
  [_db {:keys [segment-id patch]}]
  {:op         :schedule-inspection-operation
   :segment-id segment-id
   :summary    (str segment-id " の完全性検査/巡回点検の予定を提案: " (pr-str (keys patch)))
   :rationale  "検査/巡回点検/敷地内保守クルーのスケジュール調整提案のみ。最終的な予定確定は人間が行う。"
   :cites      [segment-id]
   :effect     :propose
   :value      (merge {:segment-id segment-id} patch)
   :confidence 0.88})

(defn- propose-maintenance-order
  "Draft a maintenance-crew/equipment procurement coordination request
  naming a registered contractor -- never a finalized purchase/dispatch
  order; a human always confirms procurement."
  [_db {:keys [segment-id patch]}]
  {:op         :coordinate-maintenance-order
   :segment-id segment-id
   :summary    (str segment-id " 向け保守クルー/機材の発注調整を提案: " (pr-str (keys patch)))
   :rationale  "保守クルー・機材等の請負業者発注調整提案のみ。確定発注は人間が行う。"
   :cites      [segment-id]
   :effect     :propose
   :value      (merge {:segment-id segment-id} patch)
   :confidence 0.90})

(defn- propose-integrity-concern
  "Surface an observed integrity concern (leak detection, pressure
  anomaly, corrosion, integrity-inspection failure) for HUMAN triage.
  This op ALWAYS escalates in `pipelineops.governor` -- never
  auto-committed at any phase -- regardless of how confident the advisor
  is that the concern is real. Deliberately reports the OBSERVATION
  only, never a finalization/control action, so the default rationale
  never trips the governor's `scope-excluded-terms` (see that var's
  docstring)."
  [_db {:keys [segment-id patch]}]
  {:op         :flag-integrity-concern
   :segment-id segment-id
   :summary    (str segment-id " の完全性懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale  "漏洩検知・圧力異常・腐食・検査不合格の観察事実の報告。常に人間の確認・対応が必要。"
   :cites      [segment-id]
   :effect     :propose
   :value      (merge {:segment-id segment-id} patch)
   :confidence (or (:confidence patch) 0.85)})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :log-throughput-record (propose-throughput-record _db request)
                   :schedule-inspection-operation (propose-inspection-operation _db request)
                   :coordinate-maintenance-order (propose-maintenance-order _db request)
                   :flag-integrity-concern (propose-integrity-concern _db request)
                   {})]
    ;; Test hook: allow injecting scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str " -- actually adjusted the pipeline pressure and overrode the emergency shutoff")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t       :advisor-proposal
   :op      (:op proposal)
   :segment-id (:segment-id proposal)
   :summary (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ _store request]
      (infer nil request))))
