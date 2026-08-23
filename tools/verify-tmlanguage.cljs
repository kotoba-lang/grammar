#!/usr/bin/env nbb
;; Tokenize real Kotoba source with `syntaxes/kotoba.tmLanguage.json` and assert
;; that named tokens carry named scopes.
;;
;;   npm i --no-save vscode-textmate vscode-oniguruma
;;   nbb tools/verify-tmlanguage.cljs <sample.kotoba> [...]
;;
;; ## Why this is a tool and not a test
;;
;; It runs the grammar through the SAME engine VS Code and github-linguist use
;; (vscode-textmate over Oniguruma), which is the only way to learn whether a
;; pattern that compiles also matches. That is worth having and worth NOT
;; making a dependency of this library: the suite's
;; `the-textmate-grammar-covers-every-forbidden-head` gates drift with no deps
;; at all, and this goes deeper when you ask for it.
;;
;; It earns the distinction: an early version of the generator escaped `-` and
;; `/` as if they were metacharacters outside a character class. Every pattern
;; still compiled, `--check` still said FRESH, and every hyphenated head — most
;; of the vocabulary — silently stopped matching. Only tokenizing real source
;; showed it.
;;
;; Exit: 0 pass, 1 assertion failed, 2 cannot tell (deps or grammar unreadable).

(require '["node:fs" :as fs]
         '[clojure.string :as str])

(def args (vec (drop 3 (js->clj js/process.argv))))
(def grammar-path "syntaxes/kotoba.tmLanguage.json")

(when (empty? args)
  (println "usage: nbb tools/verify-tmlanguage.cljs <sample.kotoba> [...]")
  (set! (.-exitCode js/process) 2))

;; [source-line token required-scope-prefix why]
(def cases
  [["(defn f [x] x)"       "defn"          "keyword.control"
    "core special form"]
   ["(atom 1)"             "atom"          "invalid.illegal"
    "forbidden head must render illegal — this is the no-ambient-authority invariant"]
   ["(eval x)"             "eval"          "invalid.illegal"
    "forbidden head must render illegal"]
   ["(string-concat a b)"  "string-concat" "support.function"
    "admitted predicate must be a support function"]
   ["(i64+ a b)"           "i64+"          "support.function"
    "longest-first alternation must pick i64+ over i64"]
   ["(http-fetch \"u\")"   "http-fetch"    "support.function.host-op"
    "host op must be distinguishable from an ordinary builtin"]
   ["(-> x f)"             "->"            "keyword.control.sugar"
    "sugar head"]
   [";; a comment"         ";; a comment"  "comment.line"
    "line comment"]
   ["(def k :some/keyword)" ":some/keyword" "constant.other.keyword"
    "namespaced keyword literal"]
   ["(def s \"text\")"     "text"          "string.quoted"
    "string body"]
   ["(def n 42)"           "42"            "constant.numeric"
    "integer literal"]
   ["(my-own-helper x)"    "my-own-helper" "variable.other.symbol"
    "a user symbol must NOT be claimed by any builtin alternation"]])

(def coverage-floor 60.0)

(defn- cannot-tell [msg]
  (println (str "cannot tell: " msg))
  (set! (.-exitCode js/process) 2))

(defn- run [tm onig]
  (let [wasm (fs/readFileSync (-> (js/require.resolve "vscode-oniguruma")
                                  (str/replace #"main\.js$" "onig.wasm")))]
    (-> (.loadWASM onig (.-buffer wasm))
        (.then
         (fn [_]
           (let [registry
                 (new (.-Registry tm)
                  #js {:onigLib (js/Promise.resolve
                                 #js {:createOnigScanner (fn [s] (.createOnigScanner onig s))
                                      :createOnigString  (fn [s] (.createOnigString onig s))})
                       :loadGrammar
                       (fn [_] (js/Promise.resolve
                                (.parseRawGrammar tm
                                 (fs/readFileSync grammar-path "utf8") grammar-path)))})]
             (.loadGrammar registry "source.kotoba"))))
        (.then
         (fn [g]
           (if (nil? g)
             ;; Refuse rather than pass: a grammar that failed to load would
             ;; otherwise tokenize nothing and clear every assertion vacuously.
             (cannot-tell (str "grammar failed to load from " grammar-path))
             (let [failures (atom [])]
               (doseq [[line needle scope why] cases]
                 (let [hit (some (fn [t]
                                   (let [s (subs line (.-startIndex t) (.-endIndex t))]
                                     (when (and (str/includes? needle s)
                                                (some #(str/starts-with? % scope)
                                                      (js->clj (.-scopes t))))
                                       t)))
                                 (.-tokens (.tokenizeLine g line nil)))]
                   (when-not hit
                     (swap! failures conj
                            (str "  " (pr-str line) "\n    expected " needle
                                 " to carry " scope "\n    reason: " why)))))
               (doseq [path args]
                 (let [total (atom 0) scoped (atom 0)]
                   (doseq [line (str/split-lines (fs/readFileSync path "utf8"))]
                     (doseq [t (.-tokens (.tokenizeLine g line nil))]
                       (when (seq (str/trim (subs line (.-startIndex t) (.-endIndex t))))
                         (swap! total inc)
                         (when (> (count (.-scopes t)) 1) (swap! scoped inc)))))
                   (let [pct (if (zero? @total) 0 (* 100.0 (/ @scoped @total)))]
                     (println (str "  " path "  tokens=" @total " scoped=" @scoped
                                   " (" (.toFixed pct 1) "%)"))
                     ;; A grammar can load, match a handful of tokens and clear
                     ;; every case above. The floor is what makes real source
                     ;; part of the verdict.
                     (when (zero? @total)
                       (swap! failures conj (str "  " path ": produced 0 tokens")))
                     (when (< pct coverage-floor)
                       (swap! failures conj
                              (str "  " path ": only " (.toFixed pct 1)
                                   "% of tokens carried a scope (floor "
                                   coverage-floor "%)"))))))
               (if (seq @failures)
                 (do (println "FAIL")
                     (doseq [f @failures] (println f))
                     (set! (.-exitCode js/process) 1))
                 (println (str "PASS  " (count cases) " scope cases, "
                               (count args) " sources")))))))
        (.catch (fn [e] (cannot-tell (.-message e)))))))

(when (seq args)
  (if-not (fs/existsSync grammar-path)
    (cannot-tell (str grammar-path " does not exist — run: nbb tools/gen-tmlanguage.cljs"))
    ;; `js/import` throws synchronously under nbb when the package is absent,
    ;; so it has to run INSIDE a promise callback for the throw to become a
    ;; rejection the `.catch` below can turn into "cannot tell".
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (js/Promise.all
                        #js [(js/import "vscode-textmate")
                             (js/import "vscode-oniguruma")])))
        (.then (fn [ms]
                 ;; CommonJS reached through ESM interop: the API is on
                 ;; `.default`, and `js->clj` here would mangle the module
                 ;; namespace objects.
                 (let [tm (aget ms 0) onig (aget ms 1)]
                   (run (or (.-default tm) tm) (or (.-default onig) onig)))))
        (.catch (fn [_]
                  (cannot-tell
                   "vscode-textmate / vscode-oniguruma not installed — npm i --no-save vscode-textmate vscode-oniguruma"))))))
