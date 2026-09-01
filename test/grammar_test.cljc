(ns grammar-test
  (:require [clojure.test :refer [deftest is testing]]
            #?(:clj [clojure.edn :as edn])
            [clojure.string :as str]
            [kotoba.grammar :as grammar]
            [kotoba.grammar.embedded :as embedded]))

;; Load gate: the split must not break namespace resolution. Each extracted
;; namespace must load standalone from this repo's own dependency closure.
;; (`find-ns` does not exist in ClojureScript, so the JVM keeps its original
;; assertion and both runtimes get one that a half-loaded namespace fails.)
(deftest every-extracted-namespace-loads
  #?(:clj (is (some? (find-ns 'kotoba.grammar)) "kotoba.grammar must load"))
  (is (map? (grammar/catalog)) "kotoba.grammar must load and answer"))

;; CHANGED 2026-08-18, and it is the only existing assertion this conversion
;; touched. It called the two-argument `strict-problems`, which resolves the
;; live host-import surface from `kotoba.core.contracts` — a reader the
;; dependency defines only under `#?(:clj …)`. On ClojureScript that arity
;; correctly declines to answer (see `a-strict-check-that-could-not-run-says-so`
;; below), so the test failed there for a reason that has nothing to do with
;; what it is about. It now names the host surface explicitly, which is the
;; portable arity and changes neither the forms nor the expected verdicts;
;; `#{}` is an empty but KNOWN surface, which is exactly the case these forms
;; need. The coverage that moved off this test — that the two-argument arity
;; really does read the contract off the classpath — is asserted directly by
;; `the-jvm-arity-resolves-the-live-host-surface`, so nothing was dropped.
(deftest strict-grammar-admits-declared-calls-and-rejects-unknown-heads
  (is (empty? (grammar/strict-problems
               '[(defn helper [x] (+ x 1)) (defn main [] (helper 1))]
               {} #{})))
  (let [problem (first (grammar/strict-problems
                        '[(defn main [] (ambient-shell "whoami"))] {} #{}))]
    (is (= :unknown-form (:kotoba.runtime/problem problem)))
    (is (= "ambient-shell" (:kotoba.runtime/form problem)))))

#?(:clj
   (deftest the-jvm-arity-resolves-the-live-host-surface
     ;; What the test above used to cover incidentally, asserted on purpose.
     ;; Measured 2026-08-18: 110 ops on the pinned kotoba-core-contracts, and
     ;; the same forms get the same verdict through the reading arity.
     (is (seq (grammar/host-import-ops)))
     (is (empty? (grammar/strict-problems
                  '[(defn helper [x] (+ x 1)) (defn main [] (helper 1))] {})))
     (is (= :unknown-form
            (:kotoba.runtime/problem
             (first (grammar/strict-problems
                     '[(defn main [] (ambient-shell "whoami"))] {})))))))

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

;; ── the invariants the 2026-08-18 .clj → .cljc conversion introduced ────────

#?(:clj
   (deftest the-embedded-catalog-matches-the-edn
     ;; The generated projection replaced a runtime `io/resource` read, so the
     ;; failure mode it has to guard is drift from the EDN a human edits (and
     ;; that the upstream vendor sync writes). Deliberately NOT wrapped in a
     ;; try/nil: a run that could not read the EDN must fail here rather than
     ;; report what a run that read it and found no drift reports.
     (let [on-disk (edn/read-string
                    (slurp "resources/kotoba/lang/guest-grammar.edn"))]
       (is (= on-disk embedded/catalog)
           "run: nbb tools/gen-embedded.cljs"))))

(deftest the-catalog-is-compiled-in-not-read
  (testing "the library reads the projection and nothing else"
    (is (= embedded/catalog (grammar/catalog))))
  (testing "and it is the real catalog, not the :missing stub the old
            four-way fallback produced"
    (is (seq (:forbidden-heads (grammar/catalog))))
    (is (seq (:admitted-builtins (grammar/catalog))))))

(deftest unknown-host-imports-propagate-as-nil-not-as-none
  (testing "an unavailable capability contract yields nil, never #{}"
    (is (nil? (grammar/host-import-ops nil))))
  (testing "a contract in hand answers on any runtime, without a file"
    (is (= #{'clipboard-read 'audio-play}
           (grammar/host-import-ops {:host-imports {'clipboard-read {}
                                                    'audio-play {}}}))))
  (testing "nil host ops make the admission set unknown, not empty —
            #{} would report every capability call as a grammar violation"
    (is (nil? (grammar/admitted-heads nil)))
    (is (seq (grammar/admitted-heads #{'clipboard-read})))))

(deftest a-strict-check-that-could-not-run-says-so
  (let [forms '[(defn main [] (clipboard-read "x"))]]
    (testing "with no host surface, strict mode declines rather than
              inventing an :unknown-form for every capability call"
      (let [problems (grammar/strict-problems forms {} nil)]
        (is (= 1 (count problems)))
        (is (= :grammar-unavailable
               (:kotoba.runtime/problem (first problems))))
        (is (not-any? #(= :unknown-form (:kotoba.runtime/problem %)) problems))))
    (testing "with the host surface, the same forms are clean"
      (is (empty? (grammar/strict-problems forms {} #{'clipboard-read}))))
    (testing "forbidden heads are catalog-only and are still caught with no
              host surface at all, when strict mode is off"
      (let [problems (grammar/strict-problems
                      '[(defn main [] (load-string "x"))]
                      {:kotoba.policy/strict-grammar false}
                      nil)]
        (is (= [:denied-form] (mapv :kotoba.runtime/problem problems)))))))

;; ── the TextMate projection ────────────────────────────────────────────────

#?(:clj
   (deftest the-textmate-grammar-covers-every-forbidden-head
     ;; `syntaxes/kotoba.tmLanguage.json` is the second generated projection of
     ;; this catalog (after `embedded.cljc`), and the one github-linguist and
     ;; editors read. Its whole reason to exist rather than aliasing
     ;; `source.clojure` is that it renders `:forbidden-heads` as illegal, so
     ;; that is what is asserted — against the EDN, not against a copy of the
     ;; generator's expected output, which would only prove the generator
     ;; agrees with itself.
     ;;
     ;; Not wrapped in try/nil, for the same reason as the embedded drift
     ;; check: a run that could not read either file must fail here rather
     ;; than report what a clean run reports.
     (let [catalog (edn/read-string
                    (slurp "resources/kotoba/lang/guest-grammar.edn"))
           json    (slurp "syntaxes/kotoba.tmLanguage.json")
           lines   (str/split-lines json)
           ;; The generator pretty-prints each pattern as "name" then "match",
           ;; on separate lines. Reading the name line and calling it the
           ;; pattern is how the first version of this test passed nothing and
           ;; failed everything — take the "match" line at or after the name.
           illegal (let [i (first (keep-indexed
                                   (fn [i l]
                                     (when (str/includes?
                                            l "invalid.illegal.forbidden-head.kotoba")
                                       i))
                                   lines))]
                     (when i
                       (first (filter #(str/includes? % "\"match\"")
                                      (drop i lines)))))
           ;; Heads carrying regex metacharacters (`.`, `..`, `has-capability?`)
           ;; appear escaped in the pattern, so compare against the pattern with
           ;; its backslashes removed rather than re-implementing the
           ;; generator's escaping here — a second copy of that rule is exactly
           ;; what this repo generates projections to avoid.
           illegal (some-> illegal (str/replace "\\" ""))
           heads   (map str (:forbidden-heads catalog))]
       (is (seq heads)
           "the catalog must actually carry forbidden heads")
       (is (some? illegal)
           "the grammar must scope something invalid.illegal — run: nbb tools/gen-tmlanguage.cljs")
       ;; Guarded: with no pattern found the assertion above already names the
       ;; reason, and 31 NullPointerExceptions on top of it would bury it.
       (doseq [head (if illegal heads [])]
         (is (.contains ^String illegal head)
             (str "forbidden head " head " is not scoped invalid.illegal"
                  " — run: nbb tools/gen-tmlanguage.cljs")))
       (testing "and the grammar declares the scope Linguist will reference"
         (is (.contains ^String json "\"scopeName\": \"source.kotoba\""))
         (is (.contains ^String json "\"kotoba\""))))))

;; ── :sugar entries keyed by feature rather than by head ────────────────────

(deftest sugar-heads-expands-forms-instead-of-admitting-the-feature-name
  ;; A `:sugar` entry is keyed either by a call head or by a FEATURE whose real
  ;; heads live under `:forms`. Reading only the keys admitted the feature name
  ;; — a symbol that cannot appear in source — and rejected the heads that can.
  (testing "a feature entry contributes its :forms, not its key"
    (is (= #{'defmulti 'defmethod}
           (grammar/sugar-heads {:closed-multimethod {:forms '[defmulti defmethod]}}))))
  (testing "an entry with no :forms contributes its key, which IS the head"
    (is (= #{'when} (grammar/sugar-heads {:when {:desugars-to "if + optional do"}}))))
  (testing ":forms written as prose describes a shape, so the key is kept"
    (is (= #{'nested-destructuring}
           (grammar/sugar-heads {:nested-destructuring {:forms ["nested vector/map"]}})))))

(deftest the-admission-set-holds-real-heads-and-not-feature-names
  (let [heads (grammar/admitted-heads #{})]
    (testing "heads the compiler accepts and the catalog documents"
      ;; Measured 2026-08-24 across kotoba-lang before the fix: loop and recur
      ;; appear 57 times each in compiling .kotoba source and were rejected
      ;; here. Two mechanisms for one named invariant, disagreeing.
      (doseq [head '[loop recur defmulti defmethod
                     assert! retract! observe! facet-enter! facet-leave!]]
        (is (contains? heads head) (str head " must be admitted"))))
    (testing "feature names, which cannot appear in source, are not heads"
      ;; 0 occurrences between them across the corpus. Admitting them meant a
      ;; typo'd `(loop-recur …)` would have passed the grammar gate.
      (doseq [phantom '[loop-recur closed-multimethod dataspace
                        protocol-extension variadic-comparison]]
        (is (not (contains? heads phantom))
            (str phantom " is a feature name, not a call head"))))
    (testing "and widening the set did not admit anything forbidden"
      (doseq [head (map #(symbol (name %)) (:forbidden-heads (grammar/catalog)))]
        (is (not (contains? heads head))
            (str head " is forbidden and must never be admitted"))))))

(deftest strict-grammar-accepts-loop-recur-source
  (testing "the shape that 57 real sources use"
    (is (empty? (grammar/strict-problems
                 '[(defn main [] (loop [i 0] (if (< i 3) (recur (+ i 1)) i)))]
                 {} #{}))))
  (testing "and the feature name is now the thing that fails"
    (is (= "loop-recur"
           (:kotoba.runtime/form
            (first (grammar/strict-problems '[(defn main [] (loop-recur 1))] {} #{})))))))
