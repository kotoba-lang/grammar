(ns kotoba.grammar
  "Embedded loader for the shared guest-grammar catalog (ADR-2607180900).

  P0 strict-grammar: `strict-problems` rejects forbidden heads always and,
  when strict mode is on, unknown call heads not in the admitted set.

  ## Why this is `.cljc` and not `.clj`

  Two things made this namespace JVM-only. Neither was the grammar.

  1. THE CATALOG WAS READ AT RUNTIME. `io/resource` + `java.io.PushbackReader`,
     with two more classpath probes and a cwd-relative `io/file` behind them,
     and a `:status :missing` stub catalog if all three came up empty. There is
     no portable `io/resource`, and the obvious ClojureScript substitute — read
     `resources/<path>` relative to the working directory — is right only while
     this library is the root project. So the catalog is compiled in, as the
     generated `kotoba.grammar.embedded`, projected from
     `resources/kotoba/lang/guest-grammar.edn` by `tools/gen-embedded.cljs`.
     The EDN stays the thing a human edits (and the thing the upstream vendor
     sync writes); the projection is what the code reads; `--check` refuses to
     let them drift.

     With no read there is no read that can fail, so the four-way fallback and
     its `:missing` catalog are gone with the path they guarded — a check with
     no failure mode is theatre. The failure mode that now exists is drift, and
     that is what is checked.

  2. THE LIVE HOST SURFACE STILL IS JVM-ONLY, and that has NOT been fixed here.
     `admitted-heads` unions the catalog's symbols with the ops registered on
     the runtime capability contract, and that contract is read by
     `kotoba.core.contracts/capability-contract`, which the dependency defines
     inside `#?(:clj …)`. Measured 2026-08-18: 110 host-import ops, expanding
     to 220 admitted heads out of 402 — more than half. Returning `#{}` for
     them under ClojureScript would not be a smaller answer, it would be a
     WRONG one: `strict-problems` would report `:unknown-form` against every
     capability call in a guest module and look exactly like a grammar
     violation.

     So the host ops are an explicit input. `(host-import-ops contract)` is
     pure and works everywhere; the no-argument form answers on the JVM and
     answers **nil** — not `#{}` — anywhere else. nil propagates through
     `admitted-heads`, and `strict-problems` then declines the strict half of
     its job with a `:grammar-unavailable` problem rather than inventing
     violations. Forbidden heads come from the catalog and are checked
     regardless, on both runtimes."
  (:require [clojure.string :as str]
            [kotoba.core.contracts :as core-contracts]
            [kotoba.grammar.embedded :as embedded]))

(def catalog-resource "kotoba/lang/guest-grammar.edn")

(defn catalog
  "The guest-grammar catalog.

  Reads `kotoba.grammar.embedded`, a GENERATED projection of
  `resources/kotoba/lang/guest-grammar.edn`, and touches no file at runtime."
  []
  embedded/catalog)

(defn- as-sym-set [xs]
  (into #{} (map (fn [x]
                   (cond (symbol? x) x
                         (string? x) (symbol x)
                         (keyword? x) (symbol (name x))
                         :else (symbol (str x))))
                 xs)))

(defn forbidden-heads
  []
  (as-sym-set (:forbidden-heads (catalog) #{})))

(defn string-head-host-ops
  []
  (as-sym-set (:string-head-host-ops (catalog) #{})))

(defn data-head-host-ops
  "Host-import families whose first argument is structured data rather than
  text. A constant collection literal there lowers to (bytes-ptr V)
  (bytes-len V) over canonical kotoba.value.v1 bytes -- the same (ptr,len)
  ABI, typed payload (ADR-kotoba-canonical-value-codec, VC5). Adds no call
  head, so the strict-grammar admission set is unaffected."
  []
  (as-sym-set (:data-head-host-ops (catalog) #{})))

(defn diagnostic-hint
  [head]
  (let [k (cond (string? head) head
                (symbol? head) (name head)
                :else (str head))]
    (get (:diagnostic-hints (catalog) {}) k)))

(defn with-hint
  "Assoc :kotoba.lang/hint onto PROBLEM when HEAD has a catalog entry."
  [problem head]
  (if-let [hint (diagnostic-hint head)]
    (assoc problem :kotoba.lang/hint hint)
    problem))

(defn host-import-ops
  "Ops registered on the runtime capability contract (live host surface).

  One argument: pure, portable, and the form to prefer — hand it a capability
  contract and it answers from that, on any runtime.

  No arguments: reads the contract from `kotoba.core.contracts`, which only
  defines that reader under `:clj`. Answers **nil**, not `#{}`, when it cannot
  — on ClojureScript always, and on the JVM when the contract resource is
  missing. nil says 'nobody could look'; `#{}` would say 'there are none', and
  those two produce opposite grammar verdicts."
  ([]
   #?(:clj (try
             (host-import-ops (core-contracts/capability-contract))
             (catch Throwable _ nil))
      :cljs nil))
  ([contract]
   (when (some? contract)
     (into #{} (keys (or (core-contracts/host-imports contract) {}))))))

(defn sugar-heads
  "Call heads admitted by the `:sugar` table.

  A `:sugar` entry is keyed by a call head (`:when`, `:->`) OR by a FEATURE
  whose real heads are listed under `:forms` (`:closed-multimethod` →
  `defmulti`/`defmethod`). Reading only the keys admits the feature name — a
  symbol that cannot appear in source — and rejects the heads that can.

  Measured 2026-08-24, before this read `:forms`: `strict-problems` admitted
  `loop-recur`, `closed-multimethod` and `dataspace` (0 occurrences between
  them across kotoba-lang) while rejecting `loop` and `recur` (57 each),
  `defmulti` and `defmethod`. The compiler admits those, so the same named
  invariant had two mechanisms that disagreed — the hazard `:forbidden-heads`
  already carries a note about for `atom`/`reset!`.

  `:forms` written as prose describes a shape rather than a head (only
  `:nested-destructuring` does this), so the key is kept for those."
  [sugar]
  (reduce (fn [acc [k v]]
            (let [forms (filter symbol? (:forms v))]
              (if (seq forms)
                (into acc forms)
                (conj acc (symbol (name k))))))
          #{} sugar))

(defn admitted-heads
  "Union of catalog-admitted symbols + live host-import ops.

  Propagates nil when the host ops are unknown: an admission set missing more
  than half its members is not a partial answer, it is a wrong one."
  ([] (admitted-heads (host-import-ops)))
  ([host-ops]
   (when (some? host-ops)
     (let [c (catalog)
           sugar-keys (sugar-heads (:sugar c {}))]
       (into #{}
             (concat (as-sym-set (:core-special-forms c #{}))
                     sugar-keys
                     (as-sym-set (:arithmetic c #{}))
                     (as-sym-set (:comparisons c #{}))
                     (as-sym-set (:predicates c #{}))
                     (as-sym-set (:admitted-builtins c #{}))
                     (as-sym-set (:string-head-host-ops c #{}))
                     host-ops
                     ;; with-variants of host ops
                     (map (fn [op] (symbol (str (name op) "-with")))
                          host-ops)))))))

(defn strict-grammar?
  "True when policy enables strict grammar (default ON)."
  [policy]
  (let [default? (get-in (catalog) [:strict-grammar :default] true)
        key (get-in (catalog) [:strict-grammar :policy-key]
                    :kotoba.policy/strict-grammar)]
    (if (and (map? policy) (contains? policy key))
      (boolean (get policy key))
      default?)))

(defn- list-head [form]
  (when (seq? form)
    (first form)))

(defn- walk-heads
  "Call f on every list-head symbol in form tree."
  [f form]
  (cond
    (seq? form)
    (do (when-let [h (list-head form)]
          (when (symbol? h) (f h)))
        (doseq [x form] (walk-heads f x)))
    (map? form)
    (doseq [[k v] form] (walk-heads f k) (walk-heads f v))
    (coll? form)
    (doseq [x form] (walk-heads f x))))

(defn strict-problems
  "Return grammar problems for FORMS under POLICY.
  - Always: forbidden heads from the catalog.
  - When strict-grammar?: unknown call heads not in admitted-heads.

  The three-argument arity takes the live host-import ops explicitly and is
  portable. The two-argument arity reads them from the runtime contract, which
  is JVM-only.

  When strict mode is on and the host ops are unknown, this returns a single
  `:grammar-unavailable` problem instead of a list of `:unknown-form`s. It
  cannot answer the strict question without the live surface, and a run that
  could not check must not report what a run that checked and found nothing
  reports — nor what one that checked and found 110 violations does."
  ([forms policy] (strict-problems forms policy (host-import-ops)))
  ([forms policy host-ops]
   (let [forbidden (forbidden-heads)
         declared (into #{}
                        (keep (fn [form]
                                (when (and (seq? form)
                                           (contains? '#{defn defsystem} (first form))
                                           (symbol? (second form)))
                                  (second form))))
                        forms)
         admitted (some-> (admitted-heads host-ops) (into declared))
         strict? (strict-grammar? policy)
         problems (atom [])]
     (if (and strict? (nil? admitted))
       [{:kotoba.runtime/problem :grammar-unavailable
         :kotoba.lang/grammar :strict
         :kotoba.lang/reason
         "the live host-import surface is unavailable on this runtime, so unknown call heads cannot be distinguished from capability calls"}]
       (do
         (doseq [form forms]
           (walk-heads
            (fn [head]
              (let [nm (name head)]
                (cond
                  (contains? forbidden head)
                  (swap! problems conj
                         (with-hint
                           {:kotoba.runtime/problem :denied-form
                            :kotoba.runtime/form nm
                            :kotoba.lang/grammar :forbidden}
                           head))

                  (and strict?
                       (not (contains? admitted head))
                       ;; namespaced symbols / interop-looking heads already forbidden
                       (not (str/includes? nm "/")))
                  (swap! problems conj
                         (with-hint
                           {:kotoba.runtime/problem :unknown-form
                            :kotoba.runtime/form nm
                            :kotoba.lang/grammar :strict}
                           head)))))
            form))
         @problems)))))
