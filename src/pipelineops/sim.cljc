(ns pipelineops.sim
  "Demo driver -- `clojure -M:run`. Walks a clean throughput-record
  logging request through intake -> advise -> govern -> decide ->
  approval -> commit at phase 1 (assisted-logging, always approval),
  then re-runs the same op at phase 3 (supervised-auto, clean + high
  confidence -> auto-commit), then an inspection-scheduling request and
  a low-cost maintenance-order coordination naming a verified contractor
  (both auto-commit clean at phase 3), then a high-cost maintenance
  order (ALWAYS escalates regardless of phase), then an
  integrity-concern flag (ALWAYS escalates, at any phase -- approve,
  then commit), then HARD-hold scenarios: an unregistered segment, a
  segment registered but not yet verified, a maintenance order naming an
  unverified contractor, a proposal whose own `:effect` is not
  `:propose`, and a proposal that has drifted into the
  permanently-excluded pipeline-operational-control scope."
  (:require [langgraph.graph :as g]
            [pipelineops.advisor :as advisor]
            [pipelineops.store :as store]
            [pipelineops.operation :as op]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "pipeline-administration-coordinator-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        coordinator-phase-1 {:actor-id "coord-1" :actor-role :pipeline-administration-coordinator :phase 1}
        coordinator-phase-3 {:actor-id "coord-1" :actor-role :pipeline-administration-coordinator :phase 3}
        actor (op/build db)]

    (println "== log-throughput-record segment-1 (phase 1, escalates -- human approves) ==")
    (let [r (exec-op actor "t1" {:op :log-throughput-record :segment-id "segment-1"
                                  :patch {:volume-bbl 4200 :custody-transfer-id "ct-1042"}} coordinator-phase-1)]
      (println r)
      (println "-- human pipeline-administration coordinator approves --")
      (println (approve! actor "t1")))

    (println "\n== log-throughput-record segment-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t2" {:op :log-throughput-record :segment-id "segment-1"
                                  :patch {:volume-bbl 3100 :custody-transfer-id "ct-1043"}} coordinator-phase-3))

    (println "\n== schedule-inspection-operation segment-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t3" {:op :schedule-inspection-operation :segment-id "segment-1"
                                  :patch {:kind "ili-pigging" :date "2026-08-05" :window "06:00-14:00"}} coordinator-phase-3))

    (println "\n== coordinate-maintenance-order segment-1, low cost, verified contractor (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t4" {:op :coordinate-maintenance-order :segment-id "segment-1"
                                  :patch {:item "right-of-way vegetation clearance" :estimated-cost 1200.0
                                          :contractor-id "contractor-1"}} coordinator-phase-3))

    (println "\n== coordinate-maintenance-order segment-1, HIGH cost (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t5" {:op :coordinate-maintenance-order :segment-id "segment-1"
                                 :patch {:item "corrosion-coating recoat crew mobilization" :estimated-cost 18500.0
                                         :contractor-id "contractor-1"}} coordinator-phase-3)]
      (println r)
      (println "-- human pipeline-administration coordinator reviews & approves --")
      (println (approve! actor "t5")))

    (println "\n== flag-integrity-concern segment-1 (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t6" {:op :flag-integrity-concern :segment-id "segment-1"
                                 :patch {:concern "pressure-anomaly reading on north gauge, possible slow leak near valve station 4" :confidence 0.92}} coordinator-phase-3)]
      (println r)
      (println "-- human pipeline-administration coordinator reviews & escalates to integrity team --")
      (println (approve! actor "t6")))

    (println "\n== log-throughput-record segment-99 (unregistered segment -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :log-throughput-record :segment-id "segment-99"
                                  :patch {:volume-bbl 0}} coordinator-phase-3))

    (println "\n== log-throughput-record segment-3 (registered but unverified -> HARD hold) ==")
    (println (exec-op actor "t8" {:op :log-throughput-record :segment-id "segment-3"
                                  :patch {:volume-bbl 10}} coordinator-phase-3))

    (println "\n== coordinate-maintenance-order segment-1, contractor-2 unverified (-> HARD hold) ==")
    (println (exec-op actor "t9" {:op :coordinate-maintenance-order :segment-id "segment-1"
                                  :patch {:item "right-of-way survey" :estimated-cost 900.0
                                          :contractor-id "contractor-2"}} coordinator-phase-3))

    (println "\n== schedule-inspection-operation segment-1, advisor attempts direct actuation (:effect :commit) -> HARD hold ==")
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer nil req) :effect :commit)))})]
      (println (exec-op actor-direct "t10" {:op :schedule-inspection-operation :segment-id "segment-1"
                                           :patch {:kind "hydrotest" :date "2026-08-22"}} coordinator-phase-3)))

    (println "\n== log-throughput-record segment-1, advisor drifts into pipeline-operational-control scope -> HARD hold, permanent ==")
    (println (exec-op actor "t11" {:op :log-throughput-record :segment-id "segment-1"
                                   :out-of-scope? true
                                   :patch {}} coordinator-phase-3))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== committed coordination log ==")
    (doseq [r (store/coordination-log db)] (println r))))
