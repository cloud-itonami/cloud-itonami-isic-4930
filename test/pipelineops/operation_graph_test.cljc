(ns pipelineops.operation-graph-test
  "Integration tests for `pipelineops.operation/build` -- proves the REAL
  compiled `langgraph.graph` StateGraph runs end-to-end via
  `langgraph.graph/run*` through commit / hard-hold / escalate-approve /
  escalate-reject routes. No prior test file in this repo exercised
  `operation/build` at all -- every other test covers
  governor/phase/advisor/store in isolation, which proves those pure
  functions work but not that the graph wiring actually threads them
  together."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [pipelineops.operation :as operation]
            [pipelineops.store :as store]))

(def ^:private op-context {:actor-id "operator-01" :phase 3})

(defn- exec
  ([actor tid request] (exec actor tid request op-context))
  ([actor tid request context]
   (g/run* actor {:request request :context context} {:thread-id tid})))

(deftest commit-path-log-throughput-record-auto-commits-in-phase-3
  (testing ":log-throughput-record is in phase-3's :auto set -- a clean
            proposal for a registered/verified segment commits straight
            through the REAL compiled graph with no interrupt, and the
            ledger is verified EMPTY before the run so the post-run
            fact is genuinely this run's own effect"
    (let [s (store/seed-db)
          actor (operation/build s)]
      (is (empty? (store/ledger s)))
      (let [result (exec actor "t-commit"
                         {:op :log-throughput-record :segment-id "segment-1"
                          :patch {:flow-rate-mbd 120}})
            state (:state result)]
        (is (= :done (:status result)))
        (is (= :commit (:disposition state)))
        (let [ledger (store/ledger s)]
          (is (= 1 (count ledger)))
          (is (= :committed (:t (first ledger))))
          (is (= :log-throughput-record (:op (first ledger)))))))))

(deftest hard-hold-segment-unverified-blocks-before-escalation
  (testing "segment-3 is registered but NOT verified -- a HARD governor
            violation. The real graph routes straight to :hold, never
            pausing for human approval"
    (let [s (store/seed-db)
          actor (operation/build s)]
      (is (empty? (store/ledger s)))
      (let [result (exec actor "t-hold"
                         {:op :log-throughput-record :segment-id "segment-3"
                          :patch {:flow-rate-mbd 80}})
            state (:state result)]
        (is (= :done (:status result)) "no interrupt -- HARD holds never pause for approval")
        (is (= :hold (:disposition state)))
        (let [ledger (store/ledger s)]
          (is (= 1 (count ledger)))
          (is (= :governor-hold (:t (first ledger))))
          (is (some #{:segment-unverified} (map :rule (:violations (first ledger))))))))))

(deftest hard-hold-contractor-unverified-through-compiled-graph
  (testing "an unregistered/unverified contractor-id named in a
            :coordinate-maintenance-order proposal is a HARD block,
            proven end-to-end through the compiled graph, distinct from
            the segment-unverified check"
    (let [s (store/seed-db)
          actor (operation/build s)
          result (exec actor "t-contractor-hold"
                       {:op :coordinate-maintenance-order :segment-id "segment-1"
                        :patch {:contractor-id "contractor-unknown" :estimated-cost 200.0}})
          state (:state result)]
      (is (= :done (:status result)))
      (is (= :hold (:disposition state)))
      (let [ledger (store/ledger s)]
        (is (= 1 (count ledger)))
        (is (some #{:contractor-unverified} (map :rule (:violations (first ledger)))))))))

(deftest escalate-then-approve-commits-and-genuinely-consults-advisor
  (testing ":flag-integrity-concern is NEVER in any phase's :auto set,
            so even a Governor-clean proposal GENUINELY interrupts
            (checkpointed) at :request-approval -- the ledger stays
            EMPTY until a human resumes it. Also proves the Advisor's
            real proposal (a randomly generated, single-use :concern
            string, impossible to have been hardcoded in
            pipelineops.operation) threads through
            :advise -> :govern -> :decide -> :request-approval -> :commit"
    (let [distinctive-concern (str "TEST-CONCERN-" (rand-int 1000000000))
          s (store/seed-db)
          actor (operation/build s)]
      (is (empty? (store/ledger s)))
      (let [held (exec actor "t-escalate"
                       {:op :flag-integrity-concern :segment-id "segment-1"
                        :patch {:concern distinctive-concern}})]
        (is (= :interrupted (:status held)))
        (is (= [:request-approval] (:frontier held)))
        (is (empty? (store/ledger s)) "not yet committed -- awaiting human sign-off")
        (let [approved (g/run* actor {:approval {:status :approved :by "pipeline-coordinator-01"}}
                               {:thread-id "t-escalate" :resume? true})
              approved-state (:state approved)]
          (is (= :done (:status approved)))
          (is (= :commit (:disposition approved-state)))
          (let [ledger (store/ledger s)]
            (is (= 1 (count ledger)))
            (is (= :committed (:t (first ledger))))
            (is (= :flag-integrity-concern (:op (first ledger)))))
          (let [[record] (store/coordination-log s)]
            (is (= distinctive-concern (:concern (:payload record)))
                "the committed record carries the INJECTED distinctive
                concern string -- proof the graph genuinely threads the
                Advisor's real proposal through rather than hardcoding
                a pass-string")))))))

(deftest escalate-then-reject-holds
  (testing "a human pipeline coordinator rejecting an escalated
            :flag-integrity-concern routes to :hold via the
            :request-approval node's own decision, and durably records
            the rejection -- not a hand-rolled parallel path"
    (let [s (store/seed-db)
          actor (operation/build s)
          _held (exec actor "t-reject"
                      {:op :flag-integrity-concern :segment-id "segment-2"
                       :patch {:concern "possible corrosion detected"}})
          rejected (g/run* actor {:approval {:status :rejected :by "pipeline-coordinator-01"}}
                           {:thread-id "t-reject" :resume? true})
          rejected-state (:state rejected)]
      (is (= :done (:status rejected)))
      (is (= :hold (:disposition rejected-state)))
      (let [ledger (store/ledger s)]
        (is (= 1 (count ledger)))
        (is (= :approval-rejected (:t (first ledger))))))))

(deftest high-cost-maintenance-order-escalates-never-auto-commits-even-in-phase-3
  (testing "a :coordinate-maintenance-order above maintenance-cost-
            threshold is a SOFT escalate (high-stakes) even though the
            op itself is in phase-3's :auto set and the contractor is
            genuinely verified -- proving the cost-threshold gate is
            folded into the compiled graph's :govern node, not merely
            tested in isolation"
    (let [s (store/seed-db)
          actor (operation/build s)
          result (exec actor "t-highcost"
                       {:op :coordinate-maintenance-order :segment-id "segment-1"
                        :patch {:contractor-id "contractor-1" :estimated-cost 6000.0}})]
      (is (= :interrupted (:status result))
          "high-cost maintenance order never auto-commits regardless of phase")
      (is (= [:request-approval] (:frontier result)))
      (is (empty? (store/ledger s))))))
