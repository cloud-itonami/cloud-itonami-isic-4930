(ns pipelineops.governor-test
  "Pure unit tests of `pipelineops.governor/check` against hand-built
  proposals -- the fast, focused complement to `governor-contract-test`'s
  full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [pipelineops.advisor :as adv]
            [pipelineops.governor :as gov]
            [pipelineops.store :as store]))

(def segment-1 {:segment-id "segment-1" :name "Riverside Trunk Line Segment 1"
                 :operator-license "PL-OP-1044" :registered? true :verified? true})
(def segment-3 {:segment-id "segment-3" :name "Eastfield Lateral Segment 3"
                 :operator-license "PL-OP-1122" :registered? true :verified? false})
(def contractor-1 {:contractor-id "contractor-1" :name "Northgate Pipeline Integrity Services"
                    :registered? true :verified? true})
(def contractor-2 {:contractor-id "contractor-2" :name "Unverified Right-of-Way Crew Co."
                    :registered? true :verified? false})

(defn- clean-proposal [op segment-id]
  {:op op :segment-id segment-id :summary "s" :rationale "routine pipeline administrative coordination"
   :cites [segment-id] :effect :propose :value {} :confidence 0.85})

(defn- clean-maintenance-order [segment-id contractor-id cost]
  (assoc (clean-proposal :coordinate-maintenance-order segment-id)
         :value {:segment-id segment-id :contractor-id contractor-id :estimated-cost cost}))

(deftest segment-unregistered-is-hard
  (testing "no segment record at all -> HARD hold"
    (let [s (store/mem-store {"segment-1" segment-1})
          verdict (gov/check {} nil (clean-proposal :log-throughput-record "unknown-segment") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:segment-unverified} (map :rule (:violations verdict)))))))

(deftest segment-unverified-is-hard
  (testing "segment registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"segment-3" segment-3})
          verdict (gov/check {} nil (clean-proposal :log-throughput-record "segment-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:segment-unverified} (map :rule (:violations verdict)))))))

(deftest contractor-missing-on-maintenance-order-is-hard
  (testing "maintenance-order proposal with no :contractor-id at all -> HARD hold"
    (let [s (store/mem-store {"segment-1" segment-1} {"contractor-1" contractor-1})
          verdict (gov/check {} nil (clean-maintenance-order "segment-1" nil 100.0) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:contractor-unverified} (map :rule (:violations verdict)))))))

(deftest contractor-unregistered-on-maintenance-order-is-hard
  (testing "maintenance-order proposal naming an unknown contractor -> HARD hold"
    (let [s (store/mem-store {"segment-1" segment-1} {"contractor-1" contractor-1})
          verdict (gov/check {} nil (clean-maintenance-order "segment-1" "unknown-contractor" 100.0) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:contractor-unverified} (map :rule (:violations verdict)))))))

(deftest contractor-unverified-on-maintenance-order-is-hard
  (testing "maintenance-order proposal naming a registered-but-unverified contractor -> HARD hold"
    (let [s (store/mem-store {"segment-1" segment-1} {"contractor-1" contractor-1 "contractor-2" contractor-2})
          verdict (gov/check {} nil (clean-maintenance-order "segment-1" "contractor-2" 100.0) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:contractor-unverified} (map :rule (:violations verdict)))))))

(deftest contractor-verified-on-maintenance-order-is-not-hard-on-contractor-check
  (testing "maintenance-order proposal naming a verified contractor never trips :contractor-unverified"
    (let [s (store/mem-store {"segment-1" segment-1} {"contractor-1" contractor-1})
          verdict (gov/check {} nil (clean-maintenance-order "segment-1" "contractor-1" 100.0) s)]
      (is (empty? (filter #(= :contractor-unverified (:rule %)) (:violations verdict)))))))

(deftest contractor-check-is-scoped-to-maintenance-order-only
  (testing "non-maintenance-order ops never trip :contractor-unverified, even with no contractors registered at all"
    (let [s (store/mem-store {"segment-1" segment-1})]
      (doseq [op [:log-throughput-record :schedule-inspection-operation :flag-integrity-concern]]
        (let [verdict (gov/check {} nil (clean-proposal op "segment-1") s)]
          (is (empty? (filter #(= :contractor-unverified (:rule %)) (:violations verdict)))
              (str "op " op " must never trip :contractor-unverified")))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"segment-1" segment-1})
          verdict (gov/check {} nil (assoc (clean-proposal :schedule-inspection-operation "segment-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed four-op allowlist is a scope violation"
    (let [s (store/mem-store {"segment-1" segment-1})
          verdict (gov/check {} nil (clean-proposal :adjust-pipeline-pressure "segment-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest pressure-adjustment-content-is-hard-and-permanent
  (testing "a proposal whose rationale touches directly adjusting pipeline pressure is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"segment-1" segment-1})
          poisoned (assoc (clean-proposal :log-throughput-record "segment-1")
                          :rationale "adjusted the pipeline pressure at valve station 4 to compensate for the anomaly"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest emergency-shutoff-override-content-is-hard
  (testing "a proposal touching overriding the emergency shutoff is HARD-blocked, same as pressure adjustment"
    (let [s (store/mem-store {"segment-1" segment-1})
          poisoned (assoc (clean-proposal :log-throughput-record "segment-1")
                          :rationale "overrode the emergency shutoff to keep the line flowing during the inspection"
                          :confidence 0.90)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest valve-actuation-content-is-hard
  (testing "a proposal touching actuating the valve is HARD-blocked"
    (let [s (store/mem-store {"segment-1" segment-1})
          poisoned (assoc (clean-proposal :schedule-inspection-operation "segment-1")
                          :summary "field crew should actuate the valve at the north header")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest integrity-clearance-finalization-content-is-hard
  (testing "a proposal touching finalizing the integrity-safety clearance is HARD-blocked"
    (let [s (store/mem-store {"segment-1" segment-1} {"contractor-1" contractor-1})
          poisoned (assoc (clean-maintenance-order "segment-1" "contractor-1" 100.0)
                          :summary "finalized the integrity-safety clearance ahead of the crew mobilization")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest legitimate-integrity-concern-is-not-scope-excluded
  (testing "flagging observed leak-detection/pressure-anomaly/corrosion concerns as an INTEGRITY CONCERN (not an operational-control action) never trips scope-exclusion -- this actor's core valid use case must not be self-blocked"
    (let [s (store/mem-store {"segment-1" segment-1})
          concern (assoc (clean-proposal :flag-integrity-concern "segment-1")
                         :value {:concern "pressure-anomaly reading on north gauge, possible slow leak near valve station 4"})
          verdict (gov/check {} nil concern s)]
      (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
          "raw observation content (leak/pressure-anomaly/corrosion) is exactly what this op exists to surface"))))

(deftest integrity-concern-always-escalates-clean
  (testing ":flag-integrity-concern is always high-stakes/escalate, even when otherwise clean and high confidence"
    (let [s (store/mem-store {"segment-1" segment-1})
          verdict (gov/check {} nil (assoc (clean-proposal :flag-integrity-concern "segment-1") :confidence 0.99) s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest high-cost-maintenance-order-always-escalates
  (testing "a :coordinate-maintenance-order above the cost threshold is high-stakes/escalate, even when otherwise clean and high confidence"
    (let [s (store/mem-store {"segment-1" segment-1} {"contractor-1" contractor-1})
          expensive (assoc (clean-maintenance-order "segment-1" "contractor-1" 18500.0) :confidence 0.97)
          verdict (gov/check {} nil expensive s)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict))))))

(deftest low-cost-maintenance-order-does-not-force-escalate
  (testing "a :coordinate-maintenance-order at or below the cost threshold does not trip the high-cost escalate gate"
    (let [s (store/mem-store {"segment-1" segment-1} {"contractor-1" contractor-1})
          cheap (assoc (clean-maintenance-order "segment-1" "contractor-1" 1200.0) :confidence 0.9)
          verdict (gov/check {} nil cheap s)]
      (is (false? (:hard? verdict)))
      (is (false? (:high-stakes? verdict)))
      (is (false? (:escalate? verdict))))))

;; ----------------------------- self-trip regression -----------------------------
;;
;; A known bug class in this actor fleet: the governor's own
;; scope-exclusion term list is sometimes phrased as a bare noun (e.g.
;; "pressure" or "shutoff"), which then accidentally matches inside the
;; mock advisor's own DEFAULT rationale/disclaimer text for a
;; legitimate, allowed proposal -- causing the actor to self-block its
;; own happy path. This is a dedicated regression test: every op the
;; default mock advisor can generate, with default (non-`out-of-scope?`)
;; request patches, must NEVER trip `:scope-excluded` or
;; `:op-not-allowed`.
(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "the default mock advisor's own proposals for every allowed op never trip the governor's scope-exclusion check"
    (let [s (store/mem-store {"segment-1" segment-1} {"contractor-1" contractor-1})]
      (doseq [op [:log-throughput-record :schedule-inspection-operation :coordinate-maintenance-order
                  :flag-integrity-concern]]
        (let [patch (if (= op :coordinate-maintenance-order)
                      {:item "right-of-way vegetation clearance" :estimated-cost 1200.0 :contractor-id "contractor-1"}
                      (if (= op :flag-integrity-concern)
                        {:concern "pressure-anomaly reading on north gauge, possible slow leak near valve station 4"}
                        {}))
              proposal (adv/infer nil {:op op :segment-id "segment-1" :patch patch})
              verdict (gov/check {:segment-id "segment-1"} nil proposal s)]
          (is (empty? (filter #(= :scope-excluded (:rule %)) (:violations verdict)))
              (str "default advisor proposal for " op " must never self-trip :scope-excluded -- rationale/summary: "
                   (pr-str (select-keys proposal [:summary :rationale]))))
          (is (empty? (filter #(= :op-not-allowed (:rule %)) (:violations verdict)))
              (str "default advisor proposal for " op " must always be inside the closed op allowlist")))))))
