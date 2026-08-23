#!/usr/bin/env nbb
;; Generate `syntaxes/kotoba.tmLanguage.json` from
;; `resources/kotoba/lang/guest-grammar.edn`.
;;
;;   nbb tools/gen-tmlanguage.cljs           # write
;;   nbb tools/gen-tmlanguage.cljs --check   # exit 1 if stale, 2 if it cannot tell
;;
;; ## Why a TextMate grammar lives in this repo
;;
;; This repo already owns the admissible source surface and one generated
;; projection of it (`src/kotoba/grammar/embedded.cljc`). A TextMate grammar is
;; a second projection of the same catalog into a different consumer's format:
;; editors, and github-linguist, which requires a grammar before GitHub can
;; display Kotoba as a language at all.
;;
;; Putting it anywhere else would mean a copy of the vocabulary living outside
;; the repo that owns it, drifting on its own schedule. That is the failure
;; `gen-embedded.cljs --check` was written to prevent, so this generator gets
;; the same treatment rather than a second answer to the same question.
;;
;; ## What this grammar says that source.clojure cannot
;;
;; Kotoba is Clojure-shaped, and an editor pointed at `source.clojure` renders
;; `(atom x)` and `(eval x)` as ordinary calls. They are not: `:forbidden-heads`
;; is the no-ambient-authority invariant, and the compiler fails closed on all
;; of them. Scoping those heads `invalid.illegal` is the difference between a
;; highlighter that describes Kotoba and one that describes Clojure.

(require '["node:fs" :as fs]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def edn-path "resources/kotoba/lang/guest-grammar.edn")
(def out-path "syntaxes/kotoba.tmLanguage.json")

;; ---------------------------------------------------------------- vocabulary
;; Heads arrive as symbols, keywords or strings depending on whether the name is
;; a valid bare EDN symbol (`"/"` and `"i64+"` are not). Normalise to strings.

(defn- ->names [x]
  (->> (cond (map? x) (keys x) (set? x) x (sequential? x) x :else [x])
       (map #(if (keyword? %) (name %) (str %)))
       (remove str/blank?)
       set))

;; A `:sugar` entry names either a call head (its key) or a feature whose real
;; heads are listed under `:forms`. Prefer `:forms` when they are symbols.
;; `:forms` written as prose describes a shape, not a head, so the key is used
;; and the entry is reported rather than silently treated as vocabulary.
(def ^:private sugar-prose (atom #{}))

(defn- sugar-heads [sugar]
  (reduce (fn [acc [k v]]
            (let [forms (:forms v)]
              (cond
                (and (seq forms) (every? symbol? forms)) (into acc (map str forms))
                (seq forms) (do (swap! sugar-prose conj (name k)) (conj acc (name k)))
                :else (conj acc (name k)))))
          #{} sugar))

(defn- vocabulary [authority]
  {:special   (->names (:core-special-forms authority))
   :sugar     (sugar-heads (:sugar authority))
   :forbidden (->names (:forbidden-heads authority))
   :host-op   (into (->names (:string-head-host-ops authority))
                    (->names (:data-head-host-ops authority)))
   :builtin   (reduce into #{} [(->names (:arithmetic authority))
                                (->names (:comparisons authority))
                                (->names (:predicates authority))
                                (->names (:admitted-builtins authority))])})

;; ------------------------------------------------------------------- regexes
;; Clojure-family symbols contain regex metacharacters and are not \b-delimited,
;; so bound each alternation by S-expression delimiters instead of word
;; boundaries. Longest-first so `i64+` wins over `i64`.
;;
;; Escape ONLY true metacharacters. An earlier version also escaped `-` and `/`,
;; which are literal outside a character class; the result compiled and could
;; never match, and every hyphenated head silently stopped highlighting.

(def ^:private delim "\\s()\\[\\]{}\",;'`@^~")

(defn- esc [s] (str/replace s #"[.*+?^$()|\[\]{}\\]" "\\$&"))

(defn- alternation [names]
  (->> names (sort-by (juxt (comp - count) identity)) (map esc) (str/join "|")))

(defn- head-pattern [names scope]
  {"name"  scope
   "match" (str "(?<![^" delim "])(?:" (alternation names) ")(?![^" delim "])")})

(defn- grammar [vocab]
  {"$schema" "https://raw.githubusercontent.com/martinring/tmlanguage/master/tmlanguage.json"
   "name" "Kotoba"
   "scopeName" "source.kotoba"
   "fileTypes" ["kotoba"]
   "patterns"
   [{"name" "comment.line.semicolon.kotoba" "match" ";.*$"}
    {"name" "string.quoted.double.kotoba" "begin" "\"" "end" "\""
     "patterns" [{"name" "constant.character.escape.kotoba" "match" "\\\\."}]}
    {"name" "constant.numeric.kotoba"
     "match" (str "(?<![^" delim "])[-+]?(?:0[xX][0-9a-fA-F]+|[0-9]+(?:\\.[0-9]+)?(?:[eE][-+]?[0-9]+)?)(?![^" delim "])")}
    {"name" "constant.language.kotoba"
     "match" (str "(?<![^" delim "])(?:true|false|nil)(?![^" delim "])")}
    {"name" "constant.other.keyword.kotoba" "match" "::?[a-zA-Z0-9*+!_'?<>=/.-]+"}
    (head-pattern (:forbidden vocab) "invalid.illegal.forbidden-head.kotoba")
    (head-pattern (:special   vocab) "keyword.control.kotoba")
    (head-pattern (:sugar     vocab) "keyword.control.sugar.kotoba")
    (head-pattern (:host-op   vocab) "support.function.host-op.kotoba")
    (head-pattern (:builtin   vocab) "support.function.builtin.kotoba")
    {"name" "entity.name.function.kotoba"
     "match" "(?<=\\((?:defn|def|defrecord|defprotocol|definterface)\\s)[^\\s()\\[\\]{}]+"}
    {"name" "punctuation.section.parens.kotoba" "match" "[()\\[\\]{}]"}
    {"name" "variable.other.symbol.kotoba"
     "match" (str "(?<![^" delim "])[a-zA-Z*+!_'?<>=-][^" delim "]*")}]})

;; ---------------------------------------------------------------------- main
;; `--check` distinguishes "stale" (1) from "cannot tell" (2), matching
;; gen-embedded.cljs. A run that could not read the authority must not return
;; the same code as a run that read it and found the projection current.

(defn -main [args]
  (let [check? (some #{"--check"} args)]
    (if-not (fs/existsSync edn-path)
      (do (println (str "cannot read " edn-path)) (set! (.-exitCode js/process) 2))
      (let [authority (edn/read-string (fs/readFileSync edn-path "utf8"))
            vocab (vocabulary authority)
            empty-sets (keep (fn [[k v]] (when (empty? v) k)) vocab)]
        (if (seq empty-sets)
          ;; An authority we failed to parse must not yield a grammar that
          ;; merely highlights nothing — that reads as success everywhere.
          (do (println (str "cannot tell: vocabulary " (str/join ", " empty-sets)
                            " read as empty from " edn-path))
              (set! (.-exitCode js/process) 2))
          (let [json (str (js/JSON.stringify (clj->js (grammar vocab)) nil 2) "\n")]
            (if check?
              (let [current (when (fs/existsSync out-path) (fs/readFileSync out-path "utf8"))]
                (if (= current json)
                  (println (str "FRESH " out-path))
                  (do (println (str "STALE " out-path " — regenerate: nbb tools/gen-tmlanguage.cljs"))
                      (set! (.-exitCode js/process) 1))))
              (do (fs/writeFileSync out-path json)
                  (println (str "wrote " out-path))
                  (doseq [[k v] (sort-by key vocab)]
                    (println (str "  " (name k) "=" (count v))))
                  (when (seq @sugar-prose)
                    (println (str "  note: " (count @sugar-prose)
                                  " sugar entries describe a shape rather than a head; "
                                  "key used verbatim: " (str/join ", " (sort @sugar-prose)))))))))))))

(-main (vec (drop 3 (js->clj js/process.argv))))
