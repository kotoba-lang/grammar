#!/usr/bin/env nbb
;; Generate `src/kotoba/grammar/embedded.cljc` from
;; `resources/kotoba/lang/guest-grammar.edn`.
;;
;;   nbb tools/gen-embedded.cljs           # write
;;   nbb tools/gen-embedded.cljs --check   # exit 1 if stale, 2 if it cannot tell
;;
;; ## Why embed at all
;;
;; There is no portable `io/resource`. The obvious ClojureScript substitute —
;; reading `resources/<path>` relative to the PROCESS's working directory — is
;; right only while this library is the root project and wrong the moment it is
;; a dependency. Measured elsewhere in this workspace on 2026-08-18: with
;; `kotoba-lang/technology` on that pattern, `kotoba.iso3166`'s suite under nbb
;; produced 159 errors, all of them the registry coming back nil because nbb's
;; cwd was iso3166's root and not technology's.
;;
;; The EDN file stays the source of truth and the thing a human edits — it is
;; vendored from kotoba-lang/lang/guest-grammar.edn and re-vendored by a
;; separate sync. The generated namespace is a projection of it, checked by
;; `--check`, and it is what the library actually reads. So there is no runtime
;; file access, no cwd assumption, and no classpath assumption either.
;;
;; ## The check is the failure mode that replaced the old one
;;
;; The loader this replaced tried three locations and fell back to a stub
;; catalog marked `:status :missing` — a real failure mode, honestly handled.
;; With the data compiled in there is nothing left to fail to read, so that
;; discipline is gone with the path it guarded. What CAN go wrong now is the
;; projection drifting from the EDN, so that is what is guarded: `--check`
;; here, and `the-embedded-catalog-matches-the-edn` in the suite, which fails
;; rather than passes when it cannot read the EDN.
(require '["node:fs" :as fs] '[clojure.string :as str])

(def edn-path "resources/kotoba/lang/guest-grammar.edn")
(def out-path "src/kotoba/grammar/embedded.cljc")

(defn- render [txt]
  (str ";; GENERATED — do not edit. Source: " edn-path "\n"
       ";; Regenerate: nbb tools/gen-embedded.cljs   Check: --check\n"
       ";;\n"
       ";; This is a projection of the EDN, not a second source of truth. If you\n"
       ";; edit it by hand `--check` fails, which is the whole point: two copies\n"
       ";; that can silently disagree are worse than one copy in the wrong format.\n"
       "(ns kotoba.grammar.embedded)\n\n"
       ";; Quoted, not evaluated. Unlike a registry of strings and keywords this\n"
       ";; catalog is FULL of bare symbols — `try`, `catch`, `def` — because it\n"
       ";; is a list of forbidden and admitted call HEADS. Spliced in unquoted\n"
       ";; the compiler reads them as code and the namespace does not load:\n"
       ";; measured 2026-08-18, \"Unable to resolve symbol: try in this context\".\n"
       "(def catalog\n"
       "  (quote\n"
       "   " (str/trim txt) "))\n"))

(let [args (vec *command-line-args*)
      check? (some #{"--check"} args)]
  (if-not (fs/existsSync edn-path)
    (do (println "SCANNED\t0")
        (println "Refusing to answer: no" edn-path)
        (set! (.-exitCode js/process) 2))
    (let [want (render (.toString (fs/readFileSync edn-path)))
          have (when (fs/existsSync out-path) (.toString (fs/readFileSync out-path)))]
      (println "SCANNED\t1")
      (cond
        (not check?) (do (fs/writeFileSync out-path want)
                         (println "wrote" out-path (count want) "bytes"))
        (= want have) (println "OK" out-path "matches" edn-path)
        :else (do (println "STALE" out-path "does not match" edn-path
                           "— run: nbb tools/gen-embedded.cljs")
                  (set! (.-exitCode js/process) 1))))))
