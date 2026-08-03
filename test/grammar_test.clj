(ns grammar-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.grammar :as grammar]))

;; Load gate: the split must not break namespace resolution. Each extracted
;; namespace must load standalone from this repo's own dependency closure.
(deftest every-extracted-namespace-loads
  (is (some? (find-ns 'kotoba.grammar)) "kotoba.grammar must load"))

(deftest strict-grammar-admits-declared-calls-and-rejects-unknown-heads
  (is (empty? (grammar/strict-problems
               '[(defn helper [x] (+ x 1)) (defn main [] (helper 1))]
               {})))
  (let [problem (first (grammar/strict-problems
                        '[(defn main [] (ambient-shell "whoami"))] {}))]
    (is (= :unknown-form (:kotoba.runtime/problem problem)))
    (is (= "ambient-shell" (:kotoba.runtime/form problem)))))

(deftest callable-contract-profile-is-bounded-and-abi-neutral
  (let [catalog (grammar/catalog)
        callable (:callable-type catalog)]
    (is (= 6 (:kotoba.lang.guest-grammar/profile-version catalog)))
    (is (= "[:fn [parameter-types result-type] ...]" (:syntax callable)))
    (is (= {:min 1 :max 5 :unique-by :arity} (:clauses callable)))
    (is (= {:min 0 :max 4} (:arity callable)))
    (is (= #{:i64} (:parameter-types callable)))
    (is (= :i64 (:physical-abi callable)))
    (is (= :project-interface-preserved (:module-boundary callable)))))
