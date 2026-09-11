(ns pipelineops.advisor-test
  "Unit tests of `pipelineops.advisor` proposal generation."
  (:require [clojure.test :refer [deftest is testing]]
            [pipelineops.advisor :as adv]
            [pipelineops.store :as store]))

(def db (store/seed-db))

(deftest propose-throughput-record-shape
  (testing "throughput-record proposal has correct shape and fields"
    (let [p (adv/infer db {:op :log-throughput-record
                           :segment-id "segment-1"
                           :patch {:volume-bbl 4200 :custody-transfer-id "ct-1042"}})]
      (is (= :log-throughput-record (:op p)))
      (is (= "segment-1" (:segment-id p)))
      (is (= :propose (:effect p)))
      (is (<= 0 (:confidence p) 1))
      (is (map? (:value p)))
      (is (contains? (:value p) :segment-id)))))

(deftest propose-inspection-operation-shape
  (testing "inspection-operation proposal has correct shape"
    (let [p (adv/infer db {:op :schedule-inspection-operation
                           :segment-id "segment-2"
                           :patch {:kind "ili-pigging" :date "2026-08-05"}})]
      (is (= :schedule-inspection-operation (:op p)))
      (is (= "segment-2" (:segment-id p)))
      (is (= :propose (:effect p))))))

(deftest propose-maintenance-order-shape
  (testing "maintenance-order proposal has correct shape"
    (let [p (adv/infer db {:op :coordinate-maintenance-order
                           :segment-id "segment-1"
                           :patch {:item "right-of-way vegetation clearance" :estimated-cost 1200.0
                                   :contractor-id "contractor-1"}})]
      (is (= :coordinate-maintenance-order (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p)))
      (is (= "contractor-1" (get-in p [:value :contractor-id]))))))

(deftest propose-integrity-concern-shape
  (testing "integrity-concern proposal always escalates"
    (let [p (adv/infer db {:op :flag-integrity-concern
                           :segment-id "segment-1"
                           :patch {:concern "pressure anomaly on north gauge"}})]
      (is (= :flag-integrity-concern (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest all-proposals-effect-is-always-propose
  (testing "every proposal type has :effect :propose, never direct actuation"
    (doseq [op [:log-throughput-record :schedule-inspection-operation :coordinate-maintenance-order
                :flag-integrity-concern]]
      (let [p (adv/infer db {:op op :segment-id "segment-1" :patch {}})]
        (is (= :propose (:effect p))
            (str "op " op " must have :effect :propose"))))))

(deftest rationale-string-is-present
  (testing "every proposal has a rationale explaining the advisor's thinking"
    (doseq [op [:log-throughput-record :schedule-inspection-operation :coordinate-maintenance-order
                :flag-integrity-concern]]
      (let [p (adv/infer db {:op op :segment-id "segment-1" :patch {}})]
        (is (string? (:rationale p))
            (str "op " op " must have a :rationale string"))))))
