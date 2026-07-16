(ns pipelineops.store
  "SSoT for the ISIC-4930 'Transport via pipeline' operations-COORDINATION
  actor, behind a `Store` protocol so the backend is a swap, not a
  rewrite -- the same seam every `cloud-itonami-isic-*` actor in this
  fleet uses.

  ADMINISTRATIVE / LOGISTICS COORDINATION ONLY. This actor never sets,
  adjusts or overrides a pipeline operating parameter (pressure, flow
  rate, valve state, emergency shutoff), never finalizes a
  pipeline-integrity-safety-clearance, and never itself dispatches or
  actuates anything on a live pipeline -- see `pipelineops.governor`'s
  `scope-exclusion-violations`, a HARD, permanent, un-overridable block.
  This actor coordinates the back-office administrative side of a
  pipeline-transport operator: flow-volume/billing telemetry record
  logging, pipeline-integrity-inspection / right-of-way maintenance-crew
  scheduling, integrity-concern flagging (leak detection, pressure
  anomaly, corrosion, inspection failure) for human triage, and
  maintenance-crew/equipment procurement coordination with registered
  contractors.

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/demo
  (no deps). A `segments` directory keyed by `:segment-id` STRING and a
  `contractors` directory keyed by `:contractor-id` STRING (never
  keywords -- consistent keying from the start, avoiding the silent-miss
  bug that has plagued earlier sibling actors).

  A registered/verified pipeline-segment record (operator-license +
  jurisdictional pipeline-operating registration) must exist before ANY
  proposal targeting that segment may ever commit or escalate --
  `pipelineops.governor`'s `segment-unverified-violations` re-derives
  this from the segment's own `:registered?`/`:verified?` fields, never
  from proposal self-report. A `:coordinate-maintenance-order` proposal
  additionally names a registered maintenance contractor via its own
  `:contractor-id`; the SAME 'ground truth, not self-report' discipline
  applies via `contractor-unverified-violations` -- the flagship
  genuinely new check this vertical adds (a maintenance-contractor
  counterparty-verification gate).

  The ledger stays append-only: which segment a proposal targeted, which
  operation, on what basis, committed/held/escalated and approved by whom
  is always a query over an immutable log.")

(defprotocol Store
  (segment-record [s segment-id] "Registered pipeline-segment record, or nil.
    Segment map: {:segment-id .. :name .. :operator-license .. :registered? bool :verified? bool}.")
  (all-segment-records [s])
  (contractor-record [s contractor-id] "Registered maintenance-contractor record, or nil.
    Contractor map: {:contractor-id .. :name .. :registered? bool :verified? bool}.")
  (all-contractor-records [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-segment-records [s segments] "replace/seed the segment directory (map segment-id->segment)")
  (with-contractor-records [s contractors] "replace/seed the contractor directory (map contractor-id->contractor)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained segment/contractor directory covering both the
  happy path and the governor's own hard checks, so the actor + tests run
  offline."
  []
  {:segments
   {"segment-1" {:segment-id "segment-1" :name "Riverside Trunk Line Segment 1"
                 :operator-license "PL-OP-1044" :registered? true :verified? true}
    "segment-2" {:segment-id "segment-2" :name "North Corridor Segment 2"
                 :operator-license "PL-OP-1091" :registered? true :verified? true}
    "segment-3" {:segment-id "segment-3" :name "Eastfield Lateral Segment 3 (in intake)"
                 :operator-license "PL-OP-1122" :registered? true :verified? false}}
   :contractors
   {"contractor-1" {:contractor-id "contractor-1" :name "Northgate Pipeline Integrity Services"
                     :registered? true :verified? true}
    "contractor-2" {:contractor-id "contractor-2" :name "Unverified Right-of-Way Crew Co."
                     :registered? true :verified? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (segment-record [_ segment-id] (get-in @a [:segments segment-id]))
  (all-segment-records [_] (sort-by :segment-id (vals (:segments @a))))
  (contractor-record [_ contractor-id] (get-in @a [:contractors contractor-id]))
  (all-contractor-records [_] (sort-by :contractor-id (vals (:contractors @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-segment-records [s segments] (when (seq segments) (swap! a assoc :segments segments)) s)
  (with-contractor-records [s contractors] (when (seq contractors) (swap! a assoc :contractors contractors)) s))

(defn seed-db
  "A MemStore seeded with the demo segment/contractor directory. The
  deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with explicit `segments`/`contractors` maps
  (segment-id/contractor-id string -> record map) -- the primary
  test/dev entry point. Either may be empty (an unregistered-everywhere
  segment)."
  ([segments] (mem-store segments {}))
  ([segments contractors]
   (->MemStore (atom {:segments (or segments {}) :contractors (or contractors {})
                       :ledger [] :coordination-log []}))))
