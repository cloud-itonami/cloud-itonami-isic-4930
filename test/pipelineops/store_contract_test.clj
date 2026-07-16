(ns pipelineops.store-contract-test
  "Contract tests for `pipelineops.store/Store` protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [pipelineops.store :as store]))

(deftest mem-store-segment-lookup
  (testing "MemStore can store and retrieve segments by ID (string keys)"
    (let [segments {"s1" {:segment-id "s1" :name "Alice's Trunk Line" :registered? true :verified? true}}
          s (store/mem-store segments)]
      (is (some? (store/segment-record s "s1")))
      (is (nil? (store/segment-record s "s99"))))))

(deftest mem-store-all-segment-records
  (testing "MemStore returns all segments in sorted order"
    (let [segments {"s2" {:segment-id "s2" :name "Bob's Lateral Segment"}
                     "s1" {:segment-id "s1" :name "Alice's Trunk Line"}
                     "s3" {:segment-id "s3" :name "Carol's Corridor Segment"}}
          s (store/mem-store segments)
          all-s (store/all-segment-records s)]
      (is (= 3 (count all-s)))
      (is (= "s1" (:segment-id (first all-s))))
      (is (= "s3" (:segment-id (last all-s)))))))

(deftest mem-store-contractor-lookup
  (testing "MemStore can store and retrieve contractors by ID (string keys)"
    (let [contractors {"c1" {:contractor-id "c1" :name "Acme Pipeline Integrity" :registered? true :verified? true}}
          s (store/mem-store {} contractors)]
      (is (some? (store/contractor-record s "c1")))
      (is (nil? (store/contractor-record s "c99"))))))

(deftest mem-store-all-contractor-records
  (testing "MemStore returns all contractors in sorted order"
    (let [contractors {"c2" {:contractor-id "c2" :name "Beta Crew Supply"}
                        "c1" {:contractor-id "c1" :name "Acme Pipeline Integrity"}}
          s (store/mem-store {} contractors)
          all-c (store/all-contractor-records s)]
      (is (= 2 (count all-c)))
      (is (= "c1" (:contractor-id (first all-c)))))))

(deftest mem-store-ledger-append
  (testing "MemStore append-ledger! adds facts to immutable log"
    (let [s (store/mem-store {})
          fact1 {:t :test :data "fact1"}
          fact2 {:t :test :data "fact2"}]
      (is (= 0 (count (store/ledger s))))
      (store/append-ledger! s fact1)
      (is (= 1 (count (store/ledger s))))
      (store/append-ledger! s fact2)
      (is (= 2 (count (store/ledger s)))))))

(deftest mem-store-coordination-log
  (testing "MemStore commit-record! appends to coordination-log"
    (let [s (store/mem-store {})
          record {:op :log-throughput-record :segment-id "s1" :value {:volume-bbl 4200}}]
      (is (= 0 (count (store/coordination-log s))))
      (store/commit-record! s record)
      (is (= 1 (count (store/coordination-log s))))
      (is (= record (first (store/coordination-log s)))))))

(deftest mem-store-with-segment-records
  (testing "MemStore with-segment-records replaces the segment directory"
    (let [s (store/mem-store {})
          new-segments {"s1" {:segment-id "s1" :name "Alice's Trunk Line"}}]
      (is (= 0 (count (store/all-segment-records s))))
      (store/with-segment-records s new-segments)
      (is (= 1 (count (store/all-segment-records s)))))))

(deftest mem-store-with-contractor-records
  (testing "MemStore with-contractor-records replaces the contractor directory"
    (let [s (store/mem-store {})
          new-contractors {"c1" {:contractor-id "c1" :name "Acme Pipeline Integrity"}}]
      (is (= 0 (count (store/all-contractor-records s))))
      (store/with-contractor-records s new-contractors)
      (is (= 1 (count (store/all-contractor-records s)))))))

(deftest seed-db-has-demo-data
  (testing "seed-db creates a populated MemStore with demo segments and contractors"
    (let [s (store/seed-db)]
      (is (> (count (store/all-segment-records s)) 0))
      (is (some? (store/segment-record s "segment-1")))
      (is (some? (store/segment-record s "segment-2")))
      (is (some? (store/segment-record s "segment-3")))
      (is (> (count (store/all-contractor-records s)) 0))
      (is (some? (store/contractor-record s "contractor-1")))
      (is (some? (store/contractor-record s "contractor-2"))))))

(deftest demo-data-string-key-consistency
  (testing "demo-data uses string keys, not keywords, for segment-id/contractor-id"
    (let [demo (store/demo-data)
          segments (:segments demo)
          contractors (:contractors demo)]
      (doseq [[k v] segments]
        (is (string? k) "segment keys must be strings")
        (is (string? (:segment-id v)) "segment-id must be string")
        (is (= k (:segment-id v)) "key must match segment-id"))
      (doseq [[k v] contractors]
        (is (string? k) "contractor keys must be strings")
        (is (string? (:contractor-id v)) "contractor-id must be string")
        (is (= k (:contractor-id v)) "key must match contractor-id")))))

(deftest store-is-append-only
  (testing "appended facts are immutable and never removed"
    (let [s (store/seed-db)
          fact1 {:t :event1 :data "a"}
          fact2 {:t :event2 :data "b"}]
      (store/append-ledger! s fact1)
      (let [ledger-after-1 (store/ledger s)]
        (store/append-ledger! s fact2)
        (let [ledger-after-2 (store/ledger s)]
          (is (= (count ledger-after-1) (dec (count ledger-after-2))))
          (is (every? #(some (fn [x] (= x %)) ledger-after-2) ledger-after-1)
              "all prior facts must still be present"))))))
