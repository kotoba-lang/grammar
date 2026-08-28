#!/usr/bin/env nbb
;; The portable suite on nbb — no build step, no JVM.
;;
;; This file is the point of the `.cljc` conversion. Reader conditionals that
;; are never evaluated under `:cljs` are not portability; they are the
;; appearance of it, and a `:cljs` branch nothing runs is a check that cannot
;; fail.
;;
;; ## Running it
;;
;; nbb has no dependency resolution, so the one git dependency —
;; `io.github.kotoba-lang/kotoba-core-contracts`, pinned in deps.edn — has to
;; be named on the classpath. Point at the pinned SHA under `~/.gitlibs`,
;; which `clojure -P` will have populated:
;;
;;   CC=~/.gitlibs/libs/io.github.kotoba-lang/kotoba-core-contracts/<sha>/src
;;   nbb --classpath "src:test:$CC" test/run_portable.cljs
;;
;; ## It does not have to run from the repo root
;;
;; That is the whole point of `src/kotoba/grammar/embedded.cljc`. The catalog
;; used to be read at runtime; a ClojureScript substitute that read
;; `resources/<path>` relative to the working directory would have worked here
;; and nowhere else. Run this from any directory, with absolute classpath
;; entries, and it gives the same answer.
;;
;; The ONE test that does read a file is `the-embedded-catalog-matches-the-edn`,
;; the drift check, and it is `#?(:clj …)` — it belongs to the runtime that has
;; the checkout, not to the runtime that has the compiled-in copy.
;;
;; Every `deftest`-bearing portable namespace must be named BOTH in the require
;; and in `run-tests`: requiring registers the vars, only `run-tests` runs them,
;; and a runner naming a subset prints the same `Ran N tests` shape as one
;; naming all of them.
(require '[cljs.test :as t]
         '[grammar-test]
         '[highlight-test])

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (when-not (t/successful? m) (set! (.-exitCode js/process) 1)))

(t/run-tests 'grammar-test 'highlight-test)
