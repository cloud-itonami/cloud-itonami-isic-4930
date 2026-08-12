(ns pipelineops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for `cloud-itonami-isic-4930`: this
  repo previously had NO demo page and no generator at all.

  This namespace drives the REAL actor stack --
  `pipelineops.operation` (a compiled langgraph StateGraph) ->
  `pipelineops.advisor` -> `pipelineops.governor` -> `pipelineops.phase`
  -> `pipelineops.store` -- and renders whatever that run actually
  produced. Nothing on the generated page is hand-typed: every segment,
  contractor, operator-license, disposition, governor rule, violation
  detail, confidence and approver id below is read back out of the
  store's own append-only ledger / coordination log after the graph has
  run.

  Scenario design (see `run-demo!`): one clean phase-3 auto-commit
  lifecycle on the seeded `segment-1`/`segment-2`, three
  human-approved escalations (a phase-gated write, an
  over-threshold maintenance order, an always-escalate integrity
  concern), one human-REJECTED escalation, one low-confidence
  escalation, two rollout-phase holds and SEVEN HARD governor holds
  covering all five of this governor's un-overridable rules
  (`:segment-unverified`, `:contractor-unverified`,
  `:effect-not-propose`, `:scope-excluded`, `:op-not-allowed`).

  Determinism: no timestamps, no clock reads, no randomness; every
  collection rendered is either the store's own insertion-ordered
  append-only vector or explicitly sorted. Two consecutive runs are
  byte-identical.

  `-main` REFUSES to write the file when the run produced zero HARD
  governor holds -- a console that shows no real hold would be
  indistinguishable from a hand-written mock.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [pipelineops.advisor :as advisor]
            [pipelineops.governor :as governor]
            [pipelineops.operation :as op]
            [pipelineops.phase :as phase]
            [pipelineops.store :as store]))

;; ----------------------------- the run -----------------------------

(def ^:private coordinator-1 "pipeline-administration-coordinator-1")
(def ^:private coordinator-2 "pipeline-administration-coordinator-2")

(defn- ctx [phase]
  {:actor-id "coord-1" :actor-role :pipeline-administration-coordinator :phase phase})

(defn- exec! [actor tid request phase]
  (g/run* actor {:request request :context (ctx phase)} {:thread-id tid}))

(defn- resume! [actor tid status by]
  (g/run* actor {:approval {:status status :by by}} {:thread-id tid :resume? true}))

(defn- degraded-advisor
  "The SAME mock advisor, but returning a proposal whose confidence has
  fallen under `governor/confidence-floor` -- a degraded / low-signal
  telemetry situation. Exercises the governor's confidence gate through
  the real graph rather than asserting it in prose."
  [conf]
  (reify advisor/Advisor
    (-advise [_ _store request] (assoc (advisor/infer nil request) :confidence conf))))

(defn- direct-actuation-advisor
  "A drifted advisor claiming a direct actuation (`:effect :commit`)
  instead of a proposal. Same injection point `pipelineops.sim` uses."
  []
  (reify advisor/Advisor
    (-advise [_ _store request] (assoc (advisor/infer nil request) :effect :commit))))

(defn- out-of-allowlist-advisor
  "A drifted advisor proposing an op that is not in the governor's closed
  allowlist at all."
  [op-kw]
  (reify advisor/Advisor
    (-advise [_ _store request] (assoc (advisor/infer nil request) :op op-kw))))

(defn- run-case
  "Execute one coordination request through `actor` and, when it stops at
  the human-approval interrupt and the case supplies a decision, resume
  it with that decision. Returns the full record of what happened --
  never a summary typed by hand."
  [actor {:keys [tid phase request human by] :as c}]
  (let [r (exec! actor tid request phase)
        resumed (when (and human (= :interrupted (:status r)))
                  (resume! actor tid human by))]
    (assoc c :run r :resume resumed)))

(defn run-demo!
  "Seeds a fresh `pipelineops.store/seed-db` and drives 18 coordination
  requests through the real OperationActor graph. Returns
  `{:db .. :cases [..]}` where every case carries the actual `run*`
  result (state + status), so `render` never has to be told what
  happened.

  The seeded directory (`pipelineops.store/demo-data`) is the only
  source of segment / contractor identity: `segment-1` Riverside Trunk
  Line Segment 1 (registered+verified), `segment-2` North Corridor
  Segment 2 (registered+verified), `segment-3` Eastfield Lateral
  Segment 3 (registered, NOT verified), `contractor-1` Northgate
  Pipeline Integrity Services (registered+verified), `contractor-2`
  Unverified Right-of-Way Crew Co. (registered, NOT verified). Volumes,
  custody-transfer ids, inspection dates and costs are caller-supplied
  request inputs -- this actor has no seeded throughput corpus."
  []
  (let [db      (store/seed-db)
        actor   (op/build db)
        low     (op/build db {:advisor (degraded-advisor 0.42)})
        direct  (op/build db {:advisor (direct-actuation-advisor)})
        offlist (op/build db {:advisor (out-of-allowlist-advisor :adjust-pressure-setpoint)})
        cases
        [;; --- clean phase-3 auto-commit lifecycle ---
         {:tid "a1" :phase 3 :actor actor :group :clean
          :label "throughput record, verified segment, clean"
          :request {:op :log-throughput-record :segment-id "segment-1"
                    :patch {:volume-bbl 3100 :custody-transfer-id "ct-1043"}}}
         {:tid "a2" :phase 3 :actor actor :group :clean
          :label "integrity-inspection scheduling, clean"
          :request {:op :schedule-inspection-operation :segment-id "segment-1"
                    :patch {:kind "ili-pigging" :date "2026-08-05" :window "06:00-14:00"}}}
         {:tid "a3" :phase 3 :actor actor :group :clean
          :label "maintenance order under cost threshold, verified contractor"
          :request {:op :coordinate-maintenance-order :segment-id "segment-1"
                    :patch {:item "right-of-way vegetation clearance" :estimated-cost 1200.0
                            :contractor-id "contractor-1"}}}
         {:tid "b1" :phase 3 :actor actor :group :clean
          :label "throughput record on the second verified segment"
          :request {:op :log-throughput-record :segment-id "segment-2"
                    :patch {:volume-bbl 5875 :custody-transfer-id "ct-2011"}}}

         ;; --- escalations that reach a human ---
         {:tid "c1" :phase 1 :actor actor :group :escalated
          :label "phase 1 assisted-logging: every write needs sign-off"
          :request {:op :log-throughput-record :segment-id "segment-1"
                    :patch {:volume-bbl 4200 :custody-transfer-id "ct-1042"}}
          :human :approved :by coordinator-1}
         {:tid "d1" :phase 3 :actor actor :group :escalated
          :label "maintenance order OVER the cost threshold"
          :request {:op :coordinate-maintenance-order :segment-id "segment-1"
                    :patch {:item "corrosion-coating recoat crew mobilization"
                            :estimated-cost 18500.0 :contractor-id "contractor-1"}}
          :human :approved :by coordinator-1}
         {:tid "e1" :phase 3 :actor actor :group :escalated
          :label "integrity concern -- always escalates, at every phase"
          :request {:op :flag-integrity-concern :segment-id "segment-1"
                    :patch {:concern "pressure-anomaly reading on north gauge, possible slow leak near valve station 4"
                            :confidence 0.92}}
          :human :approved :by coordinator-1}
         {:tid "g1" :phase 3 :actor low :group :escalated
          :label "degraded advisor: confidence under the floor"
          :variant "advisor confidence forced to 0.42"
          :request {:op :schedule-inspection-operation :segment-id "segment-2"
                    :patch {:kind "close-interval survey" :date "2026-09-14" :window "07:00-15:00"}}
          :human :approved :by coordinator-2}

         ;; --- reached a human, human said no ---
         {:tid "f1" :phase 3 :actor actor :group :rejected
          :label "integrity concern the reviewing coordinator REJECTS"
          :request {:op :flag-integrity-concern :segment-id "segment-2"
                    :patch {:concern "corrosion coupon reading outside tolerance at the North Corridor tie-in"
                            :confidence 0.9}}
          :human :rejected :by coordinator-2}

         ;; --- rollout-phase holds (never reach a human) ---
         {:tid "h1" :phase 0 :actor actor :group :phase-hold
          :label "phase 0 is read-only: no write op is enabled"
          :request {:op :log-throughput-record :segment-id "segment-1"
                    :patch {:volume-bbl 900 :custody-transfer-id "ct-1044"}}}
         {:tid "h2" :phase 1 :actor actor :group :phase-hold
          :label "phase 1 enables logging only, not inspection scheduling"
          :request {:op :schedule-inspection-operation :segment-id "segment-1"
                    :patch {:kind "hydrotest" :date "2026-08-22"}}}

         ;; --- HARD governor holds (never reach a human, never overridable) ---
         {:tid "i1" :phase 3 :actor actor :group :hard
          :label "segment not in the registry at all"
          :request {:op :log-throughput-record :segment-id "segment-99"
                    :patch {:volume-bbl 0}}}
         {:tid "i2" :phase 3 :actor actor :group :hard
          :label "segment registered but not yet operator-license verified"
          :request {:op :log-throughput-record :segment-id "segment-3"
                    :patch {:volume-bbl 10}}}
         {:tid "i3" :phase 3 :actor actor :group :hard
          :label "maintenance order naming an unverified contractor"
          :request {:op :coordinate-maintenance-order :segment-id "segment-1"
                    :patch {:item "right-of-way survey" :estimated-cost 900.0
                            :contractor-id "contractor-2"}}}
         {:tid "i4" :phase 3 :actor actor :group :hard
          :label "maintenance order naming no contractor at all"
          :request {:op :coordinate-maintenance-order :segment-id "segment-1"
                    :patch {:item "cathodic-protection rectifier check" :estimated-cost 640.0}}}
         {:tid "i5" :phase 3 :actor direct :group :hard
          :label "advisor claims a direct actuation instead of a proposal"
          :variant "advisor :effect forced to :commit"
          :request {:op :schedule-inspection-operation :segment-id "segment-1"
                    :patch {:kind "hydrotest" :date "2026-08-22"}}}
         {:tid "i6" :phase 3 :actor actor :group :hard
          :label "advisor drifts into pipeline operational control"
          :variant "request :out-of-scope? true"
          :request {:op :log-throughput-record :segment-id "segment-1"
                    :out-of-scope? true :patch {}}}
         {:tid "i7" :phase 3 :actor offlist :group :hard
          :label "advisor proposes an op outside the closed allowlist"
          :variant "advisor :op forced to :adjust-pressure-setpoint"
          :request {:op :log-throughput-record :segment-id "segment-1"
                    :patch {:volume-bbl 2400 :custody-transfer-id "ct-1045"}}}]]
    {:db db
     :cases (mapv (fn [{:keys [actor] :as c}] (run-case actor (dissoc c :actor))) cases)}))

;; ----------------------------- derived readings -----------------------------

(defn hard-holds
  "The governor's HARD, un-overridable holds as actually written to the
  ledger: a `:governor-hold` fact carrying at least one governor rule in
  its `:basis`. A rollout-phase hold (empty `:basis`, `:phase-reason`
  set) and a human rejection (`:t :approval-rejected`) are deliberately
  NOT counted -- both are real holds, but only these never reach a human
  AND can never be overridden by one."
  [db]
  (vec (filter #(and (= :governor-hold (:t %)) (seq (:basis %)) (nil? (:phase-reason %)))
               (store/ledger db))))

(defn- phase-holds [db]
  (vec (filter #(and (= :governor-hold (:t %)) (:phase-reason %)) (store/ledger db))))

(defn- rejected-holds [db]
  (vec (filter #(= :approval-rejected (:t %)) (store/ledger db))))

(defn- final-state [{:keys [run resume]}] (:state (or resume run)))

(defn- escalation-reason [{:keys [run]}]
  (some->> (get-in run [:state :audit])
           (filter #(= :approval-requested (:t %)))
           last
           :reason))

(defn approver-attribution
  "MEASURED, never asserted: walk the real committed records and the real
  ledger and report where (if anywhere) a human approver's id actually
  landed. Rendered as prose below, so the page self-corrects if the
  actor's `commit-record!` / approval plumbing is later changed."
  [db]
  (let [recs   (vec (store/coordination-log db))
        led    (vec (store/ledger db))
        on-val (filterv #(get-in % [:value :approved-by]) recs)
        on-pay (filterv #(get-in % [:payload :approved-by]) recs)
        on-led (filterv #(or (:by %) (:approved-by %)) led)]
    {:records      (count recs)
     :ledger-facts (count led)
     :on-value     (count on-val)
     :on-payload   (count on-pay)
     :on-ledger    (count on-led)
     :approver-ids (vec (into (sorted-set)
                              (keep #(or (get-in % [:payload :approved-by])
                                         (get-in % [:value :approved-by]))
                                    recs)))}))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw-str [v] (if (keyword? v) (name v) (str v)))

(defn- fmt-map
  "Deterministic one-line rendering of a small map -- keys sorted by name
  so the page never depends on map iteration order."
  [m]
  (if (or (nil? m) (empty? m))
    "—"
    (str/join ", " (for [[k v] (sort-by (comp str key) m)]
                     (str (kw-str k) "=" (if (string? v) v (pr-str v)))))))

(defn- yes-no [b] (if b "<span class=\"ok\">yes</span>" "<span class=\"critical\">no</span>"))

(defn- disposition-cell [d]
  (case d
    :commit   "<span class=\"ok\">commit</span>"
    :escalate "<span class=\"warn\">escalate</span>"
    :hold     "<span class=\"critical\">hold</span>"
    (str "<span class=\"muted\">" (esc (kw-str d)) "</span>")))

(defn- section [title lead & body]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       (when lead (str "    <p class=\"muted\">" lead "</p>\n"))
       (str/join body)
       "  </section>\n"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" rows) "\n"
       "      </tbody>\n"
       "    </table>\n"))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

;; ----------------------------- sections -----------------------------

(defn- last-fact-for [ledger segment-id]
  (last (filter #(= segment-id (:segment-id %)) ledger)))

(defn- last-fact-cell [f]
  (cond
    (nil? f) "<span class=\"muted\">no activity</span>"
    (= :committed (:t f)) "<span class=\"ok\">committed</span>"
    (= :approval-rejected (:t f)) "<span class=\"critical\">rejected by reviewer</span>"
    (and (= :governor-hold (:t f)) (seq (:basis f)))
    (str "<span class=\"critical\">HARD hold · " (esc (str/join ", " (map kw-str (:basis f)))) "</span>")
    (= :governor-hold (:t f))
    (str "<span class=\"warn\">phase hold · " (esc (kw-str (:phase-reason f))) "</span>")
    :else (str "<span class=\"muted\">" (esc (kw-str (:t f))) "</span>")))

(defn- segments-section [db]
  (let [led (vec (store/ledger db))]
    (section
     "Pipeline segment directory (SSoT)"
     (str "Ground truth for <code>segment-unverified</code>, the governor's first HARD rule: a proposal's own claim about its "
          "segment is never trusted &mdash; <code>pipelineops.governor</code> re-reads <code>:registered?</code>/"
          "<code>:verified?</code> from this directory on every single run.")
     (table ["Segment id" "Name" "Operator license" "Registered" "License verified" "May proceed" "Last ledger fact"]
            (for [s (store/all-segment-records db)]
              (row (str "<code>" (esc (:segment-id s)) "</code>")
                   (esc (:name s))
                   (str "<span class=\"num\">" (esc (:operator-license s)) "</span>")
                   (yes-no (:registered? s))
                   (yes-no (:verified? s))
                   (if (and (:registered? s) (:verified? s))
                     "<span class=\"ok\">yes</span>"
                     "<span class=\"critical\">no &mdash; HARD blocked</span>")
                   (last-fact-cell (last-fact-for led (:segment-id s)))))))))

(defn- contractor-used-by
  "Which committed / held runs actually named this contractor -- read
  back out of the store, not declared."
  [db contractor-id]
  (count (filter #(= contractor-id (get-in % [:value :contractor-id]))
                 (store/coordination-log db))))

(defn- contractors-section [db]
  (section
   "Maintenance-contractor directory (counterparty SSoT)"
   (str "Ground truth for <code>contractor-unverified</code> &mdash; the counterparty-verification gate this vertical adds. "
        "A <code>:coordinate-maintenance-order</code> whose drafted value names an unregistered, unverified or "
        "<em>missing</em> contractor is HARD-held; the contractor id in the proposal is re-resolved here, never believed.")
   (table ["Contractor id" "Name" "Registered" "Verified" "May be ordered from" "Committed orders this run"]
          (for [c (store/all-contractor-records db)]
            (row (str "<code>" (esc (:contractor-id c)) "</code>")
                 (esc (:name c))
                 (yes-no (:registered? c))
                 (yes-no (:verified? c))
                 (if (and (:registered? c) (:verified? c))
                   "<span class=\"ok\">yes</span>"
                   "<span class=\"critical\">no &mdash; HARD blocked</span>")
                 (str "<span class=\"num\">" (contractor-used-by db (:contractor-id c)) "</span>"))))))

(defn- gate-section []
  (let [auto3 (get-in phase/phases [3 :auto])]
    (section
     "Governor gate contract (PipelineTransportGovernor)"
     (str "Derived at build time from <code>pipelineops.governor</code> and <code>pipelineops.phase</code> themselves "
          "(<code>allowed-ops</code>, <code>always-escalate-ops</code>, <code>confidence-floor</code> = "
          (esc governor/confidence-floor) ", <code>maintenance-cost-threshold</code> = "
          (esc governor/maintenance-cost-threshold) "), not transcribed by hand. "
          "The allowlist is closed and deliberately contains no op resembling operational control of the pipeline &mdash; "
          "no pressure, flow-rate, valve or shutoff op exists here at all, not even as a gated one.")
     (table ["Op" "Phases allowed to write" "Best possible outcome at phase 3" "Extra gate"]
            (for [o (sort-by name governor/allowed-ops)]
              (row (str "<code>:" (esc (name o)) "</code>")
                   (str "<span class=\"num\">"
                        (esc (str/join ", " (sort (keep (fn [[p {:keys [writes]}]] (when (contains? writes o) p))
                                                        phase/phases))))
                        "</span>")
                   (cond
                     (contains? governor/always-escalate-ops o)
                     "<span class=\"warn\">ALWAYS human approval &mdash; never auto at any phase</span>"
                     (contains? auto3 o)
                     "<span class=\"ok\">auto-commit when governor-clean</span>"
                     :else "<span class=\"warn\">human approval</span>")
                   (cond
                     (= :coordinate-maintenance-order o)
                     (str "registered+verified contractor required (HARD); estimated cost &gt; "
                          (esc governor/maintenance-cost-threshold) " always escalates")
                     (= :flag-integrity-concern o)
                     "safety-critical: governor and phase gate agree independently that this never auto-commits"
                     :else "verified segment required (HARD); confidence below the floor escalates"))))
     "    <p class=\"muted\">Four HARD rules apply to every op regardless of phase or confidence and can never be overridden by a human approval: "
     "<code>:segment-unverified</code>, <code>:contractor-unverified</code>, <code>:effect-not-propose</code>, "
     "and scope exclusion (<code>:scope-excluded</code> / <code>:op-not-allowed</code>).</p>\n")))

(defn- phase-section []
  (section
   "Rollout phase ladder"
   (str "Read straight out of <code>pipelineops.phase/phases</code>. The phase gate can only ever add caution: "
        "a governor HOLD stays a HOLD at every phase, and no phase's auto set contains "
        "<code>:flag-integrity-concern</code> &mdash; a permanent structural fact, not a milestone still to come.")
   (table ["Phase" "Label" "Ops allowed to write" "Ops allowed to auto-commit"]
          (for [[p {:keys [label writes auto]}] (sort-by key phase/phases)]
            (row (str "<span class=\"num\">" p "</span>"
                      (when (= p phase/default-phase) " <span class=\"badge\">default</span>"))
                 (esc label)
                 (if (seq writes)
                   (str/join ", " (map #(str "<code>:" (esc (name %)) "</code>") (sort-by name writes)))
                   "<span class=\"muted\">none (read-only)</span>")
                 (if (seq auto)
                   (str/join ", " (map #(str "<code>:" (esc (name %)) "</code>") (sort-by name auto)))
                   "<span class=\"muted\">none &mdash; every write needs sign-off</span>"))))))

(defn- human-cell [{:keys [human by resume]}]
  (cond
    (nil? human) "<span class=\"muted\">never reached a human</span>"
    (nil? resume) "<span class=\"warn\">awaiting sign-off</span>"
    (= :approved human) (str "<span class=\"ok\">approved</span> by <code>" (esc by) "</code>")
    :else (str "<span class=\"critical\">rejected</span> by <code>" (esc by) "</code>")))

(defn- run-row [{:keys [tid phase label variant request run] :as c}]
  (let [st       (final-state c)
        verdict  (get-in run [:state :verdict])
        proposal (get-in run [:state :proposal])
        reason   (escalation-reason c)
        basis    (->> (:audit st)
                      (filter #(#{:governor-hold :approval-rejected} (:t %)))
                      last :basis)]
    (row (str "<code>" (esc tid) "</code>")
         (str "<span class=\"num\">" phase "</span>")
         (str (esc label)
              (when variant (str "<br><span class=\"muted\">" (esc variant) "</span>")))
         (str "<code>:" (esc (name (:op request))) "</code>"
              (when (and (:op proposal) (not= (:op proposal) (:op request)))
                (str "<br><span class=\"critical\">proposal drifted to :"
                     (esc (name (:op proposal))) "</span>")))
         (str "<code>" (esc (:segment-id request)) "</code>")
         (str "<span class=\"num\">" (esc (:confidence verdict)) "</span>")
         (cond (:hard? verdict) "<span class=\"critical\">HARD violation</span>"
               (:high-stakes? verdict) "<span class=\"warn\">high-stakes</span>"
               (:escalate? verdict) "<span class=\"warn\">escalate</span>"
               :else "<span class=\"ok\">clean</span>")
         (if reason (str "<code>:" (esc (name reason)) "</code>") "—")
         (human-cell c)
         (str (disposition-cell (:disposition st))
              (when (seq basis)
                (str "<br><span class=\"critical\">"
                     (esc (str/join ", " (map kw-str basis))) "</span>"))))))

(defn- runs-section [cases]
  (section
   "Coordination runs in this build"
   (str "Every row is one full <code>langgraph</code> StateGraph run &mdash; intake &rarr; advise &rarr; govern &rarr; decide "
        "&rarr; (approval) &rarr; commit | hold &mdash; executed while this page was generated. Confidence, verdict and "
        "disposition are read out of the run's own final state.")
   (table ["Thread" "Phase" "Scenario" "Requested op" "Segment" "Confidence" "Governor verdict" "Escalation reason" "Human decision" "Outcome"]
          (map run-row cases))))

(defn- hard-hold-section [db]
  (let [hs (hard-holds db)
        by-rule (frequencies (mapcat :basis hs))]
    (section
     (str "HARD governor holds &mdash; " (count hs) " this run")
     (str "These never reached a human and no human could have overridden them. They are the only reason this page is "
          "allowed to exist: <code>-main</code> throws and writes nothing when this section would be empty. "
          (count by-rule) " distinct rules fired: "
          (str/join ", " (for [[r n] (sort-by (comp name key) by-rule)]
                           (str "<code>:" (esc (name r)) "</code> &times;" n))) ".")
     (table ["#" "Rule" "Op" "Segment" "Advisor confidence" "Governor's own detail"]
            (map-indexed
             (fn [i f]
               (row (str "<span class=\"num\">" (inc i) "</span>")
                    (str/join "<br>" (map #(str "<code class=\"critical\">:" (esc (name %)) "</code>") (:basis f)))
                    (str "<code>:" (esc (name (:op f))) "</code>")
                    (str "<code>" (esc (:segment-id f)) "</code>")
                    (str "<span class=\"num\">" (esc (:confidence f)) "</span>")
                    (str/join "<br>" (map #(esc (:detail %)) (:violations f)))))
             hs)))))

(defn- soft-hold-section [db]
  (let [ph (phase-holds db)
        rj (rejected-holds db)]
    (section
     "Holds that are NOT governor-hard"
     (str "Kept separate on purpose. A rollout-phase hold is a deployment posture, not a compliance verdict &mdash; it "
          "disappears as the phase advances. A rejection is a hold a human actually chose. Neither counts toward the "
          "HARD-hold gate above.")
     (table ["Kind" "Op" "Segment" "Why" "Reached a human"]
            (concat
             (for [f ph]
               (row "<span class=\"warn\">rollout-phase hold</span>"
                    (str "<code>:" (esc (name (:op f))) "</code>")
                    (str "<code>" (esc (:segment-id f)) "</code>")
                    (str "<code>:" (esc (kw-str (:phase-reason f))) "</code> at phase "
                         "<span class=\"num\">" (esc (:phase f)) "</span>")
                    "<span class=\"muted\">no</span>"))
             (for [f rj]
               (row "<span class=\"critical\">reviewer rejection</span>"
                    (str "<code>:" (esc (name (:op f))) "</code>")
                    (str "<code>" (esc (:segment-id f)) "</code>")
                    (str "<code>:" (esc (str/join ", " (map kw-str (:basis f)))) "</code>")
                    "<span class=\"ok\">yes &mdash; and the human said no</span>")))))))

(defn- committed-section [db]
  (section
   (str "Committed coordination log &mdash; " (count (store/coordination-log db)) " records")
   (str "The SSoT side of the actor: the only records <code>:commit</code> ever wrote. Nothing that was held appears here.")
   (table ["#" "Op" "Segment" "Committed value" "Approver retained on the record"]
          (map-indexed
           (fn [i r]
             (row (str "<span class=\"num\">" (inc i) "</span>")
                  (str "<code>:" (esc (name (:op r))) "</code>")
                  (str "<code>" (esc (:segment-id r)) "</code>")
                  (esc (fmt-map (dissoc (:value r) :segment-id)))
                  (if-let [a (or (get-in r [:payload :approved-by]) (get-in r [:value :approved-by]))]
                    (str "<span class=\"ok\">" (esc a) "</span>"
                         " <span class=\"muted\">on <code>:"
                         (esc (if (get-in r [:value :approved-by]) "value" "payload")) "</code></span>")
                    "<span class=\"muted\">none &mdash; auto-committed, no human involved</span>")))
           (store/coordination-log db)))))

(defn- attribution-section [db]
  (let [{:keys [records ledger-facts on-value on-payload on-ledger approver-ids]} (approver-attribution db)]
    (section
     "Approver attribution &mdash; measured, not assumed"
     (str "Derived by walking the actual store after the run, so this paragraph self-corrects if the actor's approval "
          "plumbing changes. It is stated plainly rather than omitted: silence would leave you unable to tell "
          "&ldquo;nobody approved this&rdquo; from &ldquo;the store did not keep who did&rdquo;.")
     (table ["Where the approver id could live" "Records carrying it" "Reading"]
            [(row "<code>[:value :approved-by]</code> on the committed record"
                  (str "<span class=\"num\">" on-value " / " records "</span>")
                  (if (pos? on-value)
                    "<span class=\"ok\">retained</span>"
                    "<span class=\"warn\">not retained here &mdash; <code>commit-record</code> builds <code>:value</code> from the advisor's proposal, before any approval exists</span>"))
             (row "<code>[:payload :approved-by]</code> on the committed record"
                  (str "<span class=\"num\">" on-payload " / " records "</span>")
                  (if (pos? on-payload)
                    "<span class=\"ok\">retained &mdash; <code>:request-approval</code> writes the approver here and <code>MemStore/commit-record!</code> keeps the whole record</span>"
                    "<span class=\"critical\">not retained</span>"))
             (row "any fact on the append-only audit ledger"
                  (str "<span class=\"num\">" on-ledger " / " ledger-facts "</span>")
                  (if (pos? on-ledger)
                    "<span class=\"ok\">retained</span>"
                    "<span class=\"warn\">NOT retained &mdash; the <code>:approval-granted</code> fact carrying <code>:by</code> is produced by <code>:request-approval</code> into the run's audit channel, but <code>:commit</code> only appends its own <code>:committed</code> fact to the store</span>"))])
     "    <p class=\"muted\">Approver ids observed in this run: "
     (if (seq approver-ids)
       (str/join ", " (map #(str "<code>" (esc %) "</code>") approver-ids))
       "<em>none</em>")
     ". Net reading: the approver's identity <strong>"
     (if (pos? (+ on-value on-payload)) "survives onto the committed record" "does not survive into the store")
     "</strong>, but <strong>"
     (if (pos? on-ledger) "is also on the audit ledger" "is absent from the audit ledger")
     "</strong> &mdash; so &ldquo;who signed this off&rdquo; is answerable from the coordination log and not from the ledger alone. "
     "Not changed here on purpose: that is actor SSoT semantics, out of scope for a demo renderer.</p>\n")))

(defn- ledger-section [db]
  (section
   (str "Append-only audit ledger &mdash; " (count (store/ledger db)) " facts")
   "Every immutable decision fact this build produced, in the order the actor wrote it."
   (table ["#" "Fact" "Op" "Segment" "Actor" "Disposition" "Basis"]
          (map-indexed
           (fn [i f]
             (row (str "<span class=\"num\">" (inc i) "</span>")
                  (str "<code>:" (esc (kw-str (:t f))) "</code>")
                  (str "<code>:" (esc (kw-str (:op f))) "</code>")
                  (str "<code>" (esc (:segment-id f)) "</code>")
                  (esc (or (:actor f) "—"))
                  (disposition-cell (:disposition f))
                  (cond
                    (seq (:basis f)) (esc (str/join ", " (map kw-str (:basis f))))
                    (:phase-reason f) (str "<span class=\"warn\">" (esc (kw-str (:phase-reason f))) "</span>")
                    :else "—")))
           (store/ledger db)))))

(defn- summary-section [db cases]
  (let [led (store/ledger db)
        by-disp (frequencies (map #(:disposition (final-state %)) cases))]
    (section
     "This build at a glance"
     "Counts computed from the run, not declared."
     (table ["Measure" "Count"]
            [(row "coordination requests driven through the real actor"
                  (str "<span class=\"num\">" (count cases) "</span>"))
             (row "committed" (str "<span class=\"ok num\">" (get by-disp :commit 0) "</span>"))
             (row "held" (str "<span class=\"critical num\">" (get by-disp :hold 0) "</span>"))
             (row "still awaiting a human at the interrupt"
                  (str "<span class=\"warn num\">" (get by-disp :escalate 0) "</span>"))
             (row "<strong>HARD governor holds</strong> (never reach a human, never overridable)"
                  (str "<span class=\"critical num\">" (count (hard-holds db)) "</span>"))
             (row "distinct HARD rules exercised"
                  (str "<span class=\"num\">" (count (distinct (mapcat :basis (hard-holds db)))) "</span>"))
             (row "rollout-phase holds" (str "<span class=\"warn num\">" (count (phase-holds db)) "</span>"))
             (row "reviewer rejections" (str "<span class=\"critical num\">" (count (rejected-holds db)) "</span>"))
             (row "append-only ledger facts" (str "<span class=\"num\">" (count led) "</span>"))
             (row "committed coordination records"
                  (str "<span class=\"num\">" (count (store/coordination-log db)) "</span>"))]))))

;; ----------------------------- document -----------------------------

(defn render
  "Pure: `{:db .. :cases ..}` (as returned by `run-demo!`) -> the whole
  HTML document as a string. No clock reads, no randomness, no reliance
  on map iteration order."
  [{:keys [db cases]}]
  (str
   "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
   "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
   "<title>cloud-itonami-isic-4930 · Transport via pipeline · Operator Console</title>\n"
   "<style>\n" (jp-go-dds.skin/dds+skin) "\n</style></head>\n<body>\n"
   "<header class=\"bar\">\n"
   "  <h1>Transport via pipeline (ISIC 4930) — Operator Console</h1>\n"
   "</header>\n"
   "<p><span class=\"badge\">read-only sample</span> <span class=\"badge\">governor-gated</span> "
   "<span class=\"badge\">administrative / logistics coordination only</span></p>\n"
   "<p class=\"subtitle\">Generated at build time by <code>pipelineops.render-html</code> "
   "(<code>clojure -M:dev:render-html</code>) by actually running this repo's actor: "
   "<code>pipelineops.operation</code> (a compiled langgraph StateGraph) over "
   "<code>pipelineops.advisor</code> &rarr; <code>pipelineops.governor</code> &rarr; "
   "<code>pipelineops.phase</code> &rarr; <code>pipelineops.store</code>. Every id, number, verdict and "
   "violation below was read back out of the store after the run.</p>\n"
   "<div class=\"note\">\n"
   "  <p><strong>Scope.</strong> This actor coordinates the back-office side of a pipeline-transport operator: "
   "flow-volume/billing telemetry logging, integrity-inspection and right-of-way crew scheduling, "
   "maintenance-crew/equipment procurement with registered contractors, and integrity-concern flagging for human "
   "triage. It <strong>never</strong> sets, adjusts or overrides a pipeline operating parameter (pressure, flow rate, "
   "valve state, emergency shutoff), never finalizes an integrity-safety clearance, and never actuates anything on a "
   "live line. That exclusion is a permanent, un-overridable HARD block in "
   "<code>pipelineops.governor/scope-exclusion-violations</code>, exercised twice on this page.</p>\n"
   "</div>\n"
   "<main>\n"
   (summary-section db cases)
   (segments-section db)
   (contractors-section db)
   (gate-section)
   (phase-section)
   (runs-section cases)
   (hard-hold-section db)
   (soft-hold-section db)
   (committed-section db)
   (attribution-section db)
   (ledger-section db)
   "</main>\n"
   "<footer>\n"
   "  <p>Seed directory: <code>pipelineops.store/demo-data</code> — segment and contractor identity on this page comes "
   "from there and nowhere else. Volumes, custody-transfer ids, inspection dates and costs are caller-supplied request "
   "inputs; this actor has no seeded throughput corpus. The page is deterministic: no timestamps, no clock reads, no "
   "randomness, every collection either append-ordered by the store or explicitly sorted — two consecutive builds are "
   "byte-identical.</p>\n"
   "  <p>Regenerate with <code>clojure -M:dev:render-html</code>. The generator refuses to write this file if the run "
   "produces zero HARD governor holds.</p>\n"
   "</footer>\n"
   "</body></html>\n"))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db] :as result} (run-demo!)
        hs (hard-holds db)]
    (when (empty? hs)
      (throw (ex-info "no governor hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count (store/ledger db))})))
    (io/make-parents out)
    (spit out (render result))
    (println "wrote" out
             "(" (count (store/ledger db)) "ledger facts,"
             (count hs) "HARD governor holds,"
             (count (distinct (mapcat :basis hs))) "distinct HARD rules,"
             (count (store/coordination-log db)) "committed records )")))
